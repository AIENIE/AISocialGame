package com.aisocialgame.service.credit;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.credit.*;
import com.aisocialgame.repository.credit.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fireflychat.ai.v1.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Service
public class AiCreditEscrowService {
    public record Claim(AiCreditReservation reservation, boolean acquired) {}
    private final AiCreditReservationRepository reservations;
    private final CreditAccountRepository accounts;
    private final TemporaryCreditService temporary;
    private final CreditLedgerService ledger;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AiCreditEscrowService(AiCreditReservationRepository reservations,CreditAccountRepository accounts,
            TemporaryCreditService temporary,CreditLedgerService ledger,ObjectMapper json,PlatformTransactionManager manager) {
        this.reservations=reservations; this.accounts=accounts; this.temporary=temporary; this.ledger=ledger; this.json=json;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(3);
    }
    public Claim reserve(ChatCompletionsRequest request,PrepareCallBudgetResponse budget) {
        String digest=hash(request.toByteArray());
        if(!digest.equals(budget.getRequestSha256()) || budget.getBudgetId().isBlank()
                || budget.getBudgetCredential().isBlank() || budget.getMaxProviderAttempts()!=1
                || budget.getMaxTotalTokens()<=0 || budget.getMaxPromptTokens()<=0 || budget.getMaxCompletionTokens()<=0
                || budget.getMaxTotalTokens()!=Math.addExact(budget.getMaxPromptTokens(),budget.getMaxCompletionTokens()))
            throw unavailable();
        return tx.execute(status->{
            var account=accounts.findForUpdate(request.getUserId(),request.getProjectKey())
                .orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"专属积分不足，请先充值或兑换"));
            String identity=hash((request.getProjectKey()+"\0"+request.getUserId()+"\0"+request.getRequestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var existing=reservations.findByIdentityHash(identity).orElse(null);
            if(existing!=null) {
                if(!existing.requestHash.equals(digest) || !existing.budgetId.equals(budget.getBudgetId())) throw unavailable();
                return new Claim(existing,false);
            }
            if(budget.getExpiresAtEpochMs()<=System.currentTimeMillis()) throw unavailable();
            Instant now=Instant.now();
            long temp=temporary.balance(account,now);
            if(Math.addExact(temp,account.getPermanentBalance())<budget.getMaxTotalTokens())
                throw new ApiException(HttpStatus.BAD_REQUEST,"专属积分不足以预留本次 AI 调用的最高用量","AI_CREDIT_INSUFFICIENT",
                    Map.of("requiredTokens",budget.getMaxTotalTokens()));
            long reserveTemp=Math.min(temp,budget.getMaxTotalTokens());
            var portions=temporary.take(account,reserveTemp,now);
            long reservePermanent=budget.getMaxTotalTokens()-reserveTemp;
            account.setPermanentBalance(account.getPermanentBalance()-reservePermanent);
            accounts.saveAndFlush(account);
            AiCreditReservation row=new AiCreditReservation(); row.id=UUID.randomUUID().toString();
            row.identityHash=identity; row.requestHash=digest; row.requestId=request.getRequestId();
            row.projectKey=request.getProjectKey(); row.userId=request.getUserId(); row.budgetId=budget.getBudgetId();
            row.state="HELD"; row.reservedTemp=reserveTemp; row.reservedPermanent=reservePermanent;
            row.tempPortionsJson=encode(portions); row.updatedAt=System.currentTimeMillis();
            reservations.saveAndFlush(row);
            ledger.insertLedgerEntry("ai-hold:"+row.id,row.userId,"AI_RESERVE",-reserveTemp,-reservePermanent,0,0,
                "AI_BUDGET",Map.of("budgetId",row.budgetId),null,account);
            return new Claim(row,true);
        });
    }
    public void reconcile(String id,GetBudgetCallStatusResponse status) {
        tx.executeWithoutResult(transaction->{
            var row=reservations.lock(id).orElseThrow();
            if(!"HELD".equals(row.state)) return;
            if(!row.budgetId.equals(status.getBudgetId()) || !row.requestHash.equals(status.getRequestSha256())) throw unavailable();
            boolean success=status.getState()==BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED;
            boolean notSent=status.getState()==BudgetCallState.BUDGET_CALL_STATE_FAILED_NOT_SENT;
            if(!success && !notSent) { row.updatedAt=System.currentTimeMillis(); reservations.save(row); return; }
            long billed=success ? status.getTotalTokens() : 0;
            if(success && (!status.getUsageAuthoritative() || !status.hasChat()
                || status.getPromptTokens()<0 || status.getCompletionTokens()<0
                || billed!=Math.addExact(status.getPromptTokens(),status.getCompletionTokens())
                || status.getChat().getPromptTokens()!=status.getPromptTokens()
                || status.getChat().getCompletionTokens()!=status.getCompletionTokens()
                || billed>Math.addExact(row.reservedTemp,row.reservedPermanent))) throw unavailable();
            var account=accounts.findForUpdate(row.userId,row.projectKey).orElseThrow();
            Instant now=Instant.now();
            long tempBefore=temporary.balance(account,now), permanentBefore=account.getPermanentBalance();
            long debitTemp=Math.min(billed,row.reservedTemp), debitPermanent=billed-debitTemp, remainingDebit=debitTemp;
            long refundedTemp=0,expiredTemp=0;
            for(var portion:decode(row.tempPortionsJson)) {
                long consumed=Math.min(remainingDebit,portion.amount()); remainingDebit-=consumed;
                long refund=portion.amount()-consumed;
                if(portion.expiresAt()!=null && !portion.expiresAt().isAfter(now)) expiredTemp+=refund;
                else { temporary.refund(account,refund,portion.expiresAt(),now); refundedTemp+=refund; }
            }
            long refundPermanent=row.reservedPermanent-debitPermanent;
            account.setPermanentBalance(Math.addExact(account.getPermanentBalance(),refundPermanent)); accounts.save(account);
            // Undo the transfer in the audit ledger, then account for actual consumption/expiry.
            ledger.insertLedgerEntry("ai-release:"+row.id,row.userId,"AI_RELEASE",row.reservedTemp,row.reservedPermanent,0,0,
                "AI_BUDGET",Map.of("budgetId",row.budgetId,"refundedTemp",String.valueOf(refundedTemp)),null,account,
                new com.aisocialgame.integration.grpc.dto.BalanceSnapshot(0,Math.addExact(tempBefore,row.reservedTemp),
                    Math.addExact(permanentBefore,row.reservedPermanent),null));
            if(success) ledger.insertLedgerEntry("ai-consume:"+row.id,row.userId,"CONSUME",-debitTemp,-debitPermanent,0,0,"AI_BUDGET",
                Map.of("budgetId",row.budgetId,"modelKey",status.getChat().getModelKey(),"promptTokens",String.valueOf(status.getPromptTokens()),
                    "completionTokens",String.valueOf(status.getCompletionTokens()),"billedTokens",String.valueOf(billed)),null,account,
                new com.aisocialgame.integration.grpc.dto.BalanceSnapshot(0,Math.addExact(tempBefore,row.reservedTemp)-debitTemp,
                    Math.addExact(permanentBefore,row.reservedPermanent)-debitPermanent,null));
            if(expiredTemp>0) ledger.insertLedgerEntry("ai-expire:"+row.id,row.userId,"EXPIRE",-expiredTemp,0,0,0,
                "AI_BUDGET",Map.of("budgetId",row.budgetId),null,account);
            row.state=success ? "SETTLED" : "RELEASED";
            if(success) row.resultBase64=Base64.getEncoder().encodeToString(status.getChat().toByteArray());
            row.updatedAt=System.currentTimeMillis(); reservations.saveAndFlush(row);
        });
    }
    public void markChecked(String id) {
        tx.executeWithoutResult(transaction -> {
            var row = reservations.lock(id).orElseThrow();
            if ("HELD".equals(row.state)) { row.updatedAt = System.currentTimeMillis(); reservations.save(row); }
        });
    }
    private String encode(List<TemporaryCreditService.Portion> portions) {
        try { return json.writeValueAsString(portions); } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private List<TemporaryCreditService.Portion> decode(String value) {
        try { return json.readValue(value,new TypeReference<List<TemporaryCreditService.Portion>>(){}); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static ApiException unavailable() {return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 预算状态需要核对","BUDGET_RECONCILIATION_REQUIRED",Map.of());}
}
