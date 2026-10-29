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
public class AiCallSettlementService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.lab.LabBudgetService labBudgets;
    public record Claim(AiCreditReservation reservation, boolean acquired) {}
    private final AiCreditReservationRepository reservations;
    private final CreditAccountRepository accounts;
    private final TemporaryCreditService temporary;
    private final CreditLedgerService ledger;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AiCallSettlementService(AiCreditReservationRepository reservations,CreditAccountRepository accounts,
            TemporaryCreditService temporary,CreditLedgerService ledger,ObjectMapper json,PlatformTransactionManager manager) {
        this.reservations=reservations; this.accounts=accounts; this.temporary=temporary; this.ledger=ledger; this.json=json;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(3);
    }
    public static boolean pending(String state) {
        return Set.of("PENDING", "USAGE_UNKNOWN", "HELD").contains(state);
    }
    /** Registers a durable call identity without transferring any credits. */
    public Claim register(ChatCompletionsRequest request,PrepareCallBudgetResponse budget) {
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
            if (budget.hasPriceQuote()) validatedPoints(budget.getPriceQuote());
            long available = Math.addExact(temporary.balance(account,now),account.getPermanentBalance());
            if (available <= 0) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "专属积分余额不足，请先充值或兑换", "AI_CREDIT_INSUFFICIENT", Map.of("availablePoints",available));
            AiCreditReservation row=new AiCreditReservation(); row.id=UUID.randomUUID().toString();
            row.identityHash=identity; row.requestHash=digest; row.requestId=request.getRequestId();
            row.projectKey=request.getProjectKey(); row.userId=request.getUserId(); row.budgetId=budget.getBudgetId();
            row.state="PENDING"; row.reservedTemp=0; row.reservedPermanent=0;
            if (labBudgets != null) labBudgets.bind(row);
            row.tempPortionsJson="[]"; row.updatedAt=System.currentTimeMillis();
            if (budget.hasPriceQuote()) row.billingQuoteBase64=Base64.getEncoder().encodeToString(budget.getPriceQuote().toByteArray());
            reservations.saveAndFlush(row);
            return new Claim(row,true);
        });
    }
    /** Release legacy monetary holds once, retaining the original identity and pricing basis. */
    public void releaseLegacyHold(String id) {
        tx.executeWithoutResult(transaction -> {
            var row=reservations.lock(id).orElseThrow();
            if (!"HELD".equals(row.state)) return;
            var account=accounts.findForUpdate(row.userId,row.projectKey).orElseThrow();
            Instant now=Instant.now();
            long expired=0, allocation=0;
            for (var portion:decode(row.tempPortionsJson)) {
                if (portion.amount()<0) throw unavailable();
                allocation=Math.addExact(allocation,portion.amount());
                if (portion.expiresAt()!=null && !portion.expiresAt().isAfter(now)) expired=Math.addExact(expired,portion.amount());
                else temporary.refund(account,portion.amount(),portion.expiresAt(),now);
            }
            if (allocation!=row.reservedTemp || row.reservedPermanent<0) throw unavailable();
            account.setPermanentBalance(Math.addExact(account.getPermanentBalance(),row.reservedPermanent));
            accounts.saveAndFlush(account);
            ledger.insertLedgerEntry("ai-release:"+row.id,row.userId,"AI_RELEASE",row.reservedTemp,row.reservedPermanent,0,0,
                    "AI_BUDGET",Map.of("budgetId",row.budgetId,"reason","POSTPAID_MIGRATION"),null,account,
                    new com.aisocialgame.integration.grpc.dto.BalanceSnapshot(0,
                            Math.addExact(temporary.balance(account,now),expired),account.getPermanentBalance(),null));
            if (expired>0) ledger.insertLedgerEntry("ai-expire:"+row.id,row.userId,"EXPIRE",-expired,0,0,0,
                    "AI_BUDGET",Map.of("budgetId",row.budgetId),null,account);
            row.reservedTemp=0; row.reservedPermanent=0; row.state="USAGE_UNKNOWN";
            row.updatedAt=System.currentTimeMillis(); reservations.saveAndFlush(row);
        });
    }
    public void reconcile(String id,GetBudgetCallStatusResponse status) {
        // Migration commits separately: unavailable usage must never keep money frozen.
        releaseLegacyHold(id);
        tx.executeWithoutResult(transaction->{
            var row=reservations.lock(id).orElseThrow();
            if(!pending(row.state)) return;
            if(!row.budgetId.equals(status.getBudgetId()) || !row.requestHash.equals(status.getRequestSha256())) throw unavailable();
            boolean success=status.getState()==BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED;
            boolean notSent=status.getState()==BudgetCallState.BUDGET_CALL_STATE_FAILED_NOT_SENT;
            if(!success && !notSent) {
                row.state=status.getState()==BudgetCallState.BUDGET_CALL_STATE_RUNNING
                        || status.getState()==BudgetCallState.BUDGET_CALL_STATE_PREPARED ? "PENDING" : "USAGE_UNKNOWN";
                row.updatedAt=System.currentTimeMillis(); reservations.save(row); return;
            }
            long usage=success ? status.getTotalTokens() : 0;
            long billed=success ? billedPoints(row,status) : 0;
            if(success && (!status.getUsageAuthoritative() || !status.hasChat()
                || status.getPromptTokens()<0 || status.getCompletionTokens()<0 || billed<0
                || usage!=Math.addExact(status.getPromptTokens(),status.getCompletionTokens())
                || status.getChat().getPromptTokens()!=status.getPromptTokens()
                || status.getChat().getCompletionTokens()!=status.getCompletionTokens())) throw unavailable();
            if(success) {
                var account=accounts.findForUpdate(row.userId,row.projectKey).orElseThrow();
                Instant now=Instant.now();
                long debitTemp=Math.min(billed,temporary.balance(account,now));
                temporary.take(account,debitTemp,now);
                long debitPermanent=billed-debitTemp;
                account.setPermanentBalance(Math.subtractExact(account.getPermanentBalance(),debitPermanent));
                accounts.saveAndFlush(account);
                ledger.insertLedgerEntry("ai-consume:"+row.id,row.userId,"CONSUME",-debitTemp,-debitPermanent,0,0,"AI_CALL",
                        settlementMetadata(row,status,billed),null,account);
                row.resultBase64=Base64.getEncoder().encodeToString(status.getChat().toByteArray());
            }
            row.state=success ? "SETTLED" : "NOT_SENT";
            row.billedPoints=billed;
            row.billedCost=success && status.hasCharge() ? new java.math.BigDecimal(status.getCharge().getTotalCost())
                    : notSent ? java.math.BigDecimal.ZERO : null;
            row.updatedAt=System.currentTimeMillis(); reservations.saveAndFlush(row);
        });
    }
    public void markChecked(String id) {
        tx.executeWithoutResult(transaction -> {
            var row = reservations.lock(id).orElseThrow();
            if (pending(row.state)) { row.updatedAt = System.currentTimeMillis(); reservations.save(row); }
        });
    }
    private static long validatedPoints(BudgetPriceQuote quote) {
        try {
            var cost=new java.math.BigDecimal(quote.getMaxCost());
            long points=cost.multiply(java.math.BigDecimal.valueOf(100)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            if (!"CNY".equals(quote.getCurrency()) || quote.getPriceVersionId()<=0 || quote.getPointsPerCurrency()!=100
                    || cost.signum()<0 || points!=quote.getMaxCreditPoints() || quote.getPriceSha256().length()!=64) throw unavailable();
            return points;
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw unavailable(); }
    }
    private static BudgetPriceQuote quote(AiCreditReservation row) {
        try { return BudgetPriceQuote.parseFrom(Base64.getDecoder().decode(row.billingQuoteBase64)); }
        catch (Exception invalid) { throw unavailable(); }
    }
    private static long billedPoints(AiCreditReservation row,GetBudgetCallStatusResponse status) {
        if (row.billingQuoteBase64==null) return status.getTotalTokens();
        var quote=quote(row);validatedPoints(quote);
        if (!status.hasPriceQuote() || !quote.equals(status.getPriceQuote()) || !status.hasCharge()
                || !status.getChat().hasBudgetCharge() || !status.getCharge().equals(status.getChat().getBudgetCharge())) throw unavailable();
        var charge=status.getCharge();
        try {
            var cost=new java.math.BigDecimal(charge.getTotalCost());
            long points=cost.multiply(java.math.BigDecimal.valueOf(100)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            if (!charge.getCurrency().equals(quote.getCurrency()) || charge.getPriceVersionId()!=quote.getPriceVersionId()
                    || !charge.getPriceSha256().equals(quote.getPriceSha256()) || cost.signum()<0
                    || points!=charge.getCreditPoints()) throw unavailable();
            return points;
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw unavailable(); }
    }
    private static Map<String,String> settlementMetadata(AiCreditReservation row,GetBudgetCallStatusResponse status,long billed) {
        var metadata=new HashMap<>(Map.of("budgetId",row.budgetId,"modelKey",status.getChat().getModelKey(),
                "promptTokens",String.valueOf(status.getPromptTokens()),"completionTokens",String.valueOf(status.getCompletionTokens())));
        if (row.billingQuoteBase64==null) metadata.put("billedTokens",String.valueOf(billed));
        else { metadata.put("billedPoints",String.valueOf(billed)); metadata.put("currency",status.getCharge().getCurrency());
            metadata.put("totalCost",status.getCharge().getTotalCost()); metadata.put("priceVersionId",String.valueOf(status.getCharge().getPriceVersionId())); }
        return metadata;
    }
    private List<TemporaryCreditService.Portion> decode(String value) {
        try { return json.readValue(value,new TypeReference<List<TemporaryCreditService.Portion>>(){}); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static ApiException unavailable() {return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI 费用凭据暂无法确认","AI_USAGE_UNKNOWN",Map.of());}
}
