package com.aisocialgame;

import com.aisocialgame.model.credit.*;
import com.aisocialgame.repository.credit.*;
import com.aisocialgame.service.credit.*;
import fireflychat.ai.v1.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=AiSocialGameApplication.class,properties={"app.ai.budget-reconcile-delay-ms=600000"})
@ActiveProfiles("test")
class AiCallSettlementTest {
    @Autowired AiCallSettlementService escrow;
    @Autowired CreditAccountRepository accounts;
    @Autowired AiCreditReservationRepository reservations;
    @Autowired CreditTempLotRepository lots;
    @Autowired TemporaryCreditService temporary;
    @Autowired CreditLedgerEntryRepository ledger;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.aisocialgame.service.ProjectCreditService projectCredits;
    ChatCompletionsRequest request;
    PrepareCallBudgetResponse budget;
    Instant originalExpiry;
    long userId;
    @BeforeEach void setup() {
        userId=Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)+1000;
        originalExpiry=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        CreditAccount account=new CreditAccount();account.setUserId(userId);account.setProjectKey("aisocialgame");
        account.setTempBalance(80);account.setPermanentBalance(100);account.setTempExpiresAt(originalExpiry);
        accounts.saveAndFlush(account);
        request=ChatCompletionsRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).setProjectKey("aisocialgame")
            .setUserId(userId).setModel("test-model").addMessages(ChatMessage.newBuilder().setRole("user").setContent("hi")).build();
        budget=PrepareCallBudgetResponse.newBuilder().setBudgetId(UUID.randomUUID().toString()).setBudgetCredential("test-budget")
            .setRequestSha256(AiCallSettlementService.hash(request.toByteArray())).setMaxPromptTokens(100).setMaxCompletionTokens(20)
            .setPriceQuote(BudgetPriceQuote.newBuilder().setCurrency("CNY").setPriceVersionId(1).setMaxCost("1.20")
                .setPointsPerCurrency(100).setMaxCreditPoints(120).setPriceSha256("b".repeat(64)))
            .setMaxTotalTokens(120).setMaxProviderAttempts(1).setExpiresAtEpochMs(System.currentTimeMillis()+300000).build();
    }
    GetBudgetCallStatusResponse success(long input,long output) {
        var charge=BudgetCharge.newBuilder().setCurrency("CNY").setPriceVersionId(budget.getPriceQuote().getPriceVersionId())
                .setTotalCost(java.math.BigDecimal.valueOf(input+output,2).toPlainString()).setCreditPoints(input+output)
                .setPriceSha256(budget.getPriceQuote().getPriceSha256()).build();
        return GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId()).setRequestSha256(budget.getRequestSha256())
            .setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setUsageAuthoritative(true).setPromptTokens(input).setCompletionTokens(output)
            .setTotalTokens(input+output).setPriceQuote(budget.getPriceQuote()).setCharge(charge).setChat(ChatCompletionsResponse.newBuilder().setModelKey("test-model").setContent("ok")
                .setPromptTokens(input).setCompletionTokens(output).setBudgetCharge(charge)).build();
    }
    @Test void noMonetaryQuoteCannotRegisterOrDebit() {
        assertThrows(com.aisocialgame.exception.ApiException.class, () -> escrow.register(request,budget.toBuilder().clearPriceQuote().build()));
        assertEquals(180,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
    }
    CreditAccount account(){return accounts.findByUserIdAndProjectKey(userId,"aisocialgame").orElseThrow();}
    @Test void largeQuotesDoNotHoldCreditsAndConcurrentCallsMaySettleNegative() throws Exception {
        var first=escrow.register(request,budget);
        var next=request.toBuilder().setRequestId(UUID.randomUUID().toString()).build();
        var nextBudget=budget.toBuilder().setBudgetId(UUID.randomUUID().toString())
                .setRequestSha256(AiCallSettlementService.hash(next.toByteArray())).build();
        var second=escrow.register(next,nextBudget);
        assertEquals(180,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
        assertFalse(ledger.findByRequestId("ai-hold:"+first.reservation().id).isPresent());
        var nextStatus=success(100,20).toBuilder().setBudgetId(nextBudget.getBudgetId()).setRequestSha256(nextBudget.getRequestSha256()).build();
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->escrow.reconcile(first.reservation().id,success(100,20)));
            var b=executor.submit(()->escrow.reconcile(second.reservation().id,nextStatus));
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(-60,account().getPermanentBalance());
        assertEquals(0,temporary.balance(account(),Instant.now()));
        var third=next.toBuilder().setRequestId(UUID.randomUUID().toString()).build();
        var thirdBudget=nextBudget.toBuilder().setBudgetId(UUID.randomUUID().toString())
                .setRequestSha256(AiCallSettlementService.hash(third.toByteArray())).build();
        assertEquals("AI_CREDIT_INSUFFICIENT",assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->escrow.register(third,thirdBudget)).getCode());
        new TransactionTemplate(manager).executeWithoutResult(tx->{var a=accounts.findForUpdate(userId,"aisocialgame").orElseThrow();
            a.setTempBalance(61);accounts.saveAndFlush(a);});
        assertTrue(escrow.register(third,thirdBudget).acquired());
        assertEquals(-60,account().getPermanentBalance());
    }
    @Test void monetaryReceiptChargesActualPriceOnceWithoutQuoteAffordability() {
        var quote=BudgetPriceQuote.newBuilder().setCurrency("CNY").setPriceVersionId(6).setMaxCost("10.620000")
                .setPointsPerCurrency(100).setMaxCreditPoints(1062).setPriceSha256("a".repeat(64)).build();
        budget=budget.toBuilder().setPriceQuote(quote).build();
        var claim=escrow.register(request,budget);
        assertFalse(escrow.register(request,budget).acquired());
        var charge=BudgetCharge.newBuilder().setCurrency("CNY").setPriceVersionId(6).setTotalCost("0.020001")
                .setCreditPoints(3).setPriceSha256(quote.getPriceSha256()).build();
        var status=success(20,10).toBuilder().setPriceQuote(quote).setCharge(charge)
                .setChat(success(20,10).getChat().toBuilder().setBudgetCharge(charge)).build();
        assertThrows(RuntimeException.class,()->escrow.reconcile(claim.reservation().id,status.toBuilder().clearCharge().build()));
        assertEquals(180,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
        escrow.reconcile(claim.reservation().id,status);escrow.reconcile(claim.reservation().id,status);
        assertEquals(77,temporary.balance(account(),Instant.now()));assertEquals(100,account().getPermanentBalance());
        assertEquals(3,reservations.findById(claim.reservation().id).orElseThrow().billedPoints);
        assertEquals(-3,ledger.findByRequestId("ai-consume:"+claim.reservation().id).orElseThrow().getTokenDeltaTemp());
    }
    @Test void concurrentRegistrationDoesNotTransferCreditsAndKeepsOneIdentity() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->escrow.register(request,budget));var b=executor.submit(()->escrow.register(request,budget));
            assertNotEquals(a.get(10,TimeUnit.SECONDS).acquired(),b.get(10,TimeUnit.SECONDS).acquired());
        }
        assertEquals(100,account().getPermanentBalance());assertEquals(80,account().getTempBalance());
    }
    @Test void unknownUsageDoesNotDebitOrPreventIndependentCalls() {
        var claim=escrow.register(request,budget);
        escrow.reconcile(claim.reservation().id,GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId())
                .setRequestSha256(budget.getRequestSha256()).setState(BudgetCallState.BUDGET_CALL_STATE_RECONCILIATION_REQUIRED).build());
        var row=reservations.findById(claim.reservation().id).orElseThrow();
        assertEquals("USAGE_UNKNOWN",row.state);assertNull(row.billedPoints);
        assertEquals(180,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
        var next=request.toBuilder().setRequestId(UUID.randomUUID().toString()).build();
        assertTrue(escrow.register(next,budget.toBuilder().setBudgetId(UUID.randomUUID().toString())
                .setRequestSha256(AiCallSettlementService.hash(next.toByteArray())).build()).acquired());
    }
    @Test void missingUsageCannotSettleAndConfirmedNotSentHasNoDebit() {
        var claim=escrow.register(request,budget);
        assertThrows(RuntimeException.class,()->escrow.reconcile(claim.reservation().id,success(20,10).toBuilder().setUsageAuthoritative(false).build()));
        assertEquals("PENDING",reservations.findById(claim.reservation().id).orElseThrow().state);
        escrow.reconcile(claim.reservation().id,GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId())
            .setRequestSha256(budget.getRequestSha256()).setState(BudgetCallState.BUDGET_CALL_STATE_FAILED_NOT_SENT).build());
        assertEquals(180,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
        assertEquals("NOT_SENT",reservations.findById(claim.reservation().id).orElseThrow().state);
        assertFalse(ledger.findByRequestId("ai-consume:"+claim.reservation().id).isPresent());
    }
    @Test void historicalBalanceImportCannotOverwritePostpaidDebits() {
        var claim=escrow.register(request,budget);
        assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->projectCredits.migrateFromPayServiceSnapshot(userId,80,100,0,"test"));
        escrow.reconcile(claim.reservation().id,success(20,10));
        assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->projectCredits.migrateFromPayServiceSnapshot(userId,80,100,0,"test"));
        assertEquals(150,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
    }
}
