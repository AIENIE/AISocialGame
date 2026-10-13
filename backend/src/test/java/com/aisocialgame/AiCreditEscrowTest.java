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
class AiCreditEscrowTest {
    @Autowired AiCreditEscrowService escrow;
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
            .setRequestSha256(AiCreditEscrowService.hash(request.toByteArray())).setMaxPromptTokens(100).setMaxCompletionTokens(20)
            .setMaxTotalTokens(120).setMaxProviderAttempts(1).setExpiresAtEpochMs(System.currentTimeMillis()+300000).build();
    }
    GetBudgetCallStatusResponse success(long input,long output) {
        return GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId()).setRequestSha256(budget.getRequestSha256())
            .setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setUsageAuthoritative(true).setPromptTokens(input).setCompletionTokens(output)
            .setTotalTokens(input+output).setChat(ChatCompletionsResponse.newBuilder().setModelKey("test-model").setContent("ok")
                .setPromptTokens(input).setCompletionTokens(output)).build();
    }
    CreditAccount account(){return accounts.findByUserIdAndProjectKey(userId,"aisocialgame").orElseThrow();}
    @Test void reserveAndSettleOncePreserveTempExpiryAcrossNewGrants() {
        var claim=escrow.reserve(request,budget);assertTrue(claim.acquired());assertFalse(escrow.reserve(request,budget).acquired());
        assertEquals(0,account().getTempBalance());assertEquals(60,account().getPermanentBalance());
        new TransactionTemplate(manager).executeWithoutResult(tx->{var a=accounts.findForUpdate(userId,"aisocialgame").orElseThrow();
            a.setTempBalance(50);a.setTempExpiresAt(originalExpiry.plusSeconds(86400));accounts.saveAndFlush(a);});
        escrow.reconcile(claim.reservation().id,success(20,10));escrow.reconcile(claim.reservation().id,success(20,10));
        assertEquals(100,account().getPermanentBalance());
        assertEquals(100,temporary.balance(account(),Instant.now()));
        var returned=lots.findByUserIdAndProjectKeyAndRemainingGreaterThan(userId,"aisocialgame",0);
        assertEquals(1,returned.size());assertEquals(50,returned.getFirst().remaining);assertEquals(originalExpiry,returned.getFirst().expiresAt);
        assertEquals("SETTLED",reservations.findById(claim.reservation().id).orElseThrow().state);
        assertTrue(ledger.findByRequestId("ai-consume:"+claim.reservation().id).isPresent());
    }
    @Test void historicalMigrationCannotOverwriteReservedOrSettledBalances() {
        var claim=escrow.reserve(request,budget);
        assertThrows(com.aisocialgame.exception.ApiException.class,
            ()->projectCredits.migrateFromPayServiceSnapshot(userId,80,100,0,"test"));
        assertEquals(60,account().getPermanentBalance());
        escrow.reconcile(claim.reservation().id,success(20,10));
        assertThrows(com.aisocialgame.exception.ApiException.class,
            ()->projectCredits.migrateFromPayServiceSnapshot(userId,80,100,0,"test"));
        assertEquals(150,temporary.balance(account(),Instant.now())+account().getPermanentBalance());
    }
    @Test void concurrentReservationTransfersCreditsOnlyOnce() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->escrow.reserve(request,budget));var b=executor.submit(()->escrow.reserve(request,budget));
            assertNotEquals(a.get(10,TimeUnit.SECONDS).acquired(),b.get(10,TimeUnit.SECONDS).acquired());
        }
        assertEquals(60,account().getPermanentBalance());assertEquals(0,account().getTempBalance());
    }
    @Test void unknownStatusKeepsHoldAndInsufficientBalanceNeverCreatesAnother() {
        var claim=escrow.reserve(request,budget);
        var unknown=GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId()).setRequestSha256(budget.getRequestSha256())
            .setState(BudgetCallState.BUDGET_CALL_STATE_RECONCILIATION_REQUIRED).build();
        escrow.reconcile(claim.reservation().id,unknown);
        assertEquals("HELD",reservations.findById(claim.reservation().id).orElseThrow().state);assertEquals(60,account().getPermanentBalance());
        var next=request.toBuilder().setRequestId(UUID.randomUUID().toString()).build();
        var nextBudget=budget.toBuilder().setBudgetId(UUID.randomUUID().toString()).setRequestSha256(AiCreditEscrowService.hash(next.toByteArray())).build();
        assertThrows(RuntimeException.class,()->escrow.reserve(next,nextBudget));
        assertEquals(60,account().getPermanentBalance());
    }
    @Test void provenNotSentReleasesAndMissingUsageCannotSettle() {
        var claim=escrow.reserve(request,budget);
        assertThrows(RuntimeException.class,()->escrow.reconcile(claim.reservation().id,success(20,10).toBuilder().setUsageAuthoritative(false).build()));
        assertEquals("HELD",reservations.findById(claim.reservation().id).orElseThrow().state);
        escrow.reconcile(claim.reservation().id,GetBudgetCallStatusResponse.newBuilder().setBudgetId(budget.getBudgetId())
            .setRequestSha256(budget.getRequestSha256()).setState(BudgetCallState.BUDGET_CALL_STATE_FAILED_NOT_SENT).build());
        assertEquals(80,temporary.balance(account(),Instant.now()));assertEquals(100,account().getPermanentBalance());
        assertEquals("RELEASED",reservations.findById(claim.reservation().id).orElseThrow().state);
    }
    @Test void settlementCommitFailureRollsBackMoneyAndKeepsHold() {
        var claim=escrow.reserve(request,budget);
        // A corrupt escrow allocation cannot be partially settled or release permanent funds.
        new TransactionTemplate(manager).executeWithoutResult(tx->{var row=reservations.lock(claim.reservation().id).orElseThrow();
            row.tempPortionsJson="invalid";reservations.saveAndFlush(row);});
        assertThrows(RuntimeException.class,()->escrow.reconcile(claim.reservation().id,success(20,10)));
        assertEquals(60,account().getPermanentBalance());assertEquals("HELD",reservations.findById(claim.reservation().id).orElseThrow().state);
        assertFalse(ledger.findByRequestId("ai-consume:"+claim.reservation().id).isPresent());
    }
    @Test void expiredTemporaryRefundNeverBecomesSpendableAgain() {
        var claim=escrow.reserve(request,budget);
        new TransactionTemplate(manager).executeWithoutResult(tx->{var row=reservations.lock(claim.reservation().id).orElseThrow();
            row.tempPortionsJson="[{\"amount\":80,\"expiresAt\":\"2000-01-01T00:00:00Z\"}]";reservations.saveAndFlush(row);});
        escrow.reconcile(claim.reservation().id,success(20,10));
        assertEquals(0,temporary.balance(account(),Instant.now()));assertEquals(100,account().getPermanentBalance());
        assertEquals(-50,ledger.findByRequestId("ai-expire:"+claim.reservation().id).orElseThrow().getTokenDeltaTemp());
    }
}
