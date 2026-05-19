package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.CheckinResult;
import com.aisocialgame.integration.grpc.dto.RedeemResult;
import com.aisocialgame.model.credit.CreditAccount;
import com.aisocialgame.model.credit.CreditCheckinRecord;
import com.aisocialgame.model.credit.CreditExchangeTransaction;
import com.aisocialgame.model.credit.CreditLedgerEntry;
import com.aisocialgame.model.credit.CreditRedeemCode;
import com.aisocialgame.model.credit.CreditRedemptionRecord;
import com.aisocialgame.repository.credit.CreditAccountRepository;
import com.aisocialgame.repository.credit.CreditCheckinRecordRepository;
import com.aisocialgame.repository.credit.CreditExchangeTransactionRepository;
import com.aisocialgame.repository.credit.CreditLedgerEntryRepository;
import com.aisocialgame.repository.credit.CreditRedeemCodeRepository;
import com.aisocialgame.repository.credit.CreditRedemptionRecordRepository;
import com.aisocialgame.service.CreditExchangeResult;
import com.aisocialgame.service.ProjectCreditService;
import com.aisocialgame.service.credit.CreditLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCreditServiceTest {

    @Mock
    private CreditAccountRepository creditAccountRepository;
    @Mock
    private CreditLedgerEntryRepository creditLedgerEntryRepository;
    @Mock
    private CreditCheckinRecordRepository creditCheckinRecordRepository;
    @Mock
    private CreditRedeemCodeRepository creditRedeemCodeRepository;
    @Mock
    private CreditRedemptionRecordRepository creditRedemptionRecordRepository;
    @Mock
    private CreditExchangeTransactionRepository creditExchangeTransactionRepository;
    @Mock
    private BillingGrpcClient billingGrpcClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ProjectCreditService projectCreditService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setProjectKey("aisocialgame");
        appProperties.getCredit().setCheckinGrantTokens(20);
        appProperties.getCredit().setExchangeDailyLimit(100);
        appProperties.getCredit().setRedeemFailureLimitPerDay(2);
        appProperties.getCredit().setTempExpiryDays(30);
        CreditLedgerService creditLedgerService = new CreditLedgerService(
                creditLedgerEntryRepository,
                appProperties,
                new ObjectMapper()
        );

        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        projectCreditService = new ProjectCreditService(
                creditAccountRepository,
                creditLedgerEntryRepository,
                creditCheckinRecordRepository,
                creditRedeemCodeRepository,
                creditRedemptionRecordRepository,
                creditExchangeTransactionRepository,
                billingGrpcClient,
                appProperties,
                creditLedgerService,
                transactionManager
        );
    }

    @Test
    void checkinShouldBeIdempotentForToday() {
        long userId = 1001L;
        CreditCheckinRecord record = new CreditCheckinRecord();
        record.setUserId(userId);
        record.setProjectKey("aisocialgame");
        record.setCheckinDate(LocalDate.now());
        when(creditCheckinRecordRepository.findByUserIdAndProjectKeyAndCheckinDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.of(record));
        when(creditAccountRepository.findByUserIdAndProjectKey(userId, "aisocialgame"))
                .thenReturn(Optional.empty());

        CheckinResult result = projectCreditService.checkin(userId, 5L);

        Assertions.assertTrue(result.success());
        Assertions.assertTrue(result.alreadyCheckedIn());
        Assertions.assertEquals(0L, result.tokensGranted());
        verify(creditCheckinRecordRepository, never()).save(any());
        verify(creditLedgerEntryRepository, never()).save(any());
    }

    @Test
    void redeemCodeShouldRejectWhenDailyFailureLimitExceeded() {
        when(creditRedemptionRecordRepository.countByUserIdAndProjectKeyAndSuccessFalseAndCreatedAtBetween(
                anyLong(), anyString(), any(), any()
        )).thenReturn(2L);

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.redeemCode(1001L, "abc", 0)
        );
        Assertions.assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
    }

    @Test
    void redeemCodeShouldRecordFailureWhenCodeInvalid() {
        when(creditRedemptionRecordRepository.countByUserIdAndProjectKeyAndSuccessFalseAndCreatedAtBetween(
                anyLong(), anyString(), any(), any()
        )).thenReturn(0L);
        when(creditRedemptionRecordRepository.existsByUserIdAndProjectKeyAndCodeAndSuccessTrue(anyLong(), anyString(), anyString()))
                .thenReturn(false);
        when(creditRedeemCodeRepository.findForUpdate("INVALID-CODE")).thenReturn(Optional.empty());
        when(creditAccountRepository.findByUserIdAndProjectKey(1001L, "aisocialgame")).thenReturn(Optional.empty());

        RedeemResult result = projectCreditService.redeemCode(1001L, "invalid-code", 3L);

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("兑换码无效", result.errorMessage());
        ArgumentCaptor<CreditRedemptionRecord> captor = ArgumentCaptor.forClass(CreditRedemptionRecord.class);
        verify(creditRedemptionRecordRepository).save(captor.capture());
        Assertions.assertFalse(captor.getValue().isSuccess());
        Assertions.assertEquals("INVALID-CODE", captor.getValue().getCode());
    }

    @Test
    void createRedeemCodeShouldNormalizeCodeAndSave() {
        when(creditRedeemCodeRepository.findByCode("ASG-1234")).thenReturn(Optional.empty());
        when(creditRedeemCodeRepository.save(any(CreditRedeemCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditRedeemCode created = projectCreditService.createRedeemCode(
                "asg-1234",
                1234L,
                "CREDIT_TYPE_PERMANENT",
                1,
                null,
                null,
                true
        );

        Assertions.assertEquals("ASG-1234", created.getCode());
        Assertions.assertEquals("CREDIT_TYPE_PERMANENT", created.getCreditType());
        Assertions.assertEquals(1234L, created.getTokens());
        Assertions.assertEquals(1, created.getMaxRedemptions());
        Assertions.assertTrue(created.isActive());
        verify(creditRedeemCodeRepository).save(any(CreditRedeemCode.class));
    }

    @Test
    void createRedeemCodeShouldRejectDuplicateCode() {
        CreditRedeemCode existing = new CreditRedeemCode();
        existing.setCode("ASG-EXISTS");
        when(creditRedeemCodeRepository.findByCode("ASG-EXISTS")).thenReturn(Optional.of(existing));

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.createRedeemCode("asg-exists", 10, "CREDIT_TYPE_TEMP", null, null, null, true)
        );

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        Assertions.assertEquals("兑换码已存在", ex.getMessage());
    }

    @Test
    void exchangeShouldRejectWhenExceedDailyLimit() {
        when(creditExchangeTransactionRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(creditExchangeTransactionRepository.sumSuccessTokensBetween(anyLong(), anyString(), any(), any()))
                .thenReturn(80L);

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.exchangePublicToProject(1001L, 30L, "req-1")
        );

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        Assertions.assertEquals("超出当日可兑换上限", ex.getMessage());
        verify(billingGrpcClient, never()).convertPublicToProject(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void exchangeShouldReturnExistingSuccessWhenRequestIdRepeated() {
        CreditExchangeTransaction txn = new CreditExchangeTransaction();
        txn.setRequestId("req-ok");
        txn.setStatus("SUCCESS");
        txn.setProjectTokens(30);
        when(creditExchangeTransactionRepository.findByRequestId("req-ok")).thenReturn(Optional.of(txn));
        when(billingGrpcClient.getPublicPermanentTokens(1001L)).thenReturn(50L);
        CreditAccount account = new CreditAccount();
        account.setUserId(1001L);
        account.setProjectKey("aisocialgame");
        account.setTempBalance(0);
        account.setPermanentBalance(12);
        when(creditAccountRepository.findByUserIdAndProjectKey(1001L, "aisocialgame"))
                .thenReturn(Optional.of(account));

        CreditExchangeResult result = projectCreditService.exchangePublicToProject(1001L, 30L, "req-ok");

        Assertions.assertEquals("req-ok", result.requestId());
        Assertions.assertEquals(30L, result.exchangedTokens());
        Assertions.assertEquals(new BalanceSnapshot(50, 0, 12, null), result.balance());
        verify(billingGrpcClient, never()).convertPublicToProject(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void exchangeShouldPersistFailedWhenRemoteConvertFails() {
        CreditAccount account = new CreditAccount();
        account.setUserId(1001L);
        account.setProjectKey("aisocialgame");
        account.setPermanentBalance(12L);
        AtomicReference<CreditExchangeTransaction> saved = new AtomicReference<>();

        when(billingGrpcClient.getPublicPermanentTokens(1001L)).thenReturn(80L);
        when(creditExchangeTransactionRepository.findByRequestId("req-fail"))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(creditExchangeTransactionRepository.sumSuccessTokensBetween(anyLong(), anyString(), any(), any()))
                .thenReturn(0L);
        when(creditAccountRepository.findForUpdate(1001L, "aisocialgame")).thenReturn(Optional.of(account));
        when(creditExchangeTransactionRepository.save(any(CreditExchangeTransaction.class))).thenAnswer(invocation -> {
            CreditExchangeTransaction txn = invocation.getArgument(0);
            saved.set(txn);
            return txn;
        });
        when(billingGrpcClient.convertPublicToProject("req-fail", "aisocialgame", 1001L, 30L))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "pay-service timeout"));

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.exchangePublicToProject(1001L, 30L, "req-fail")
        );

        Assertions.assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        Assertions.assertNotNull(saved.get());
        Assertions.assertEquals("FAILED", saved.get().getStatus());
        Assertions.assertTrue(saved.get().isRetriable());
        Assertions.assertEquals("pay-service timeout", saved.get().getFailReason());
    }

    @Test
    void reverseShouldRejectWhenAlreadyReversed() {
        CreditLedgerEntry original = new CreditLedgerEntry();
        original.setUserId(1001L);
        original.setRequestId("consume-1");
        when(creditLedgerEntryRepository.findByRequestId("consume-1")).thenReturn(Optional.of(original));
        when(creditLedgerEntryRepository.existsByRelatedEntryId(null)).thenReturn(true);

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.reverseByRequestId(1001L, "consume-1", "rollback", "admin")
        );

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        Assertions.assertEquals("该流水已冲正", ex.getMessage());
    }

    @Test
    void consumeProjectTokensShouldDeductTempThenPermanent() {
        CreditAccount account = new CreditAccount();
        account.setUserId(1001L);
        account.setProjectKey("aisocialgame");
        account.setTempBalance(200L);
        account.setPermanentBalance(1000L);
        when(creditLedgerEntryRepository.findByRequestId("consume-req")).thenReturn(Optional.empty());
        when(creditAccountRepository.findForUpdate(1001L, "aisocialgame")).thenReturn(Optional.of(account));
        when(creditAccountRepository.save(any(CreditAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingGrpcClient.getPublicPermanentTokens(1001L)).thenReturn(0L);

        projectCreditService.consumeProjectTokens(1001L, 250L, "AI_CHAT", Map.of("modelKey", "gemini"), "consume-req");

        Assertions.assertEquals(0L, account.getTempBalance());
        Assertions.assertEquals(950L, account.getPermanentBalance());

        ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
        verify(creditLedgerEntryRepository).save(captor.capture());
        Assertions.assertEquals("CONSUME", captor.getValue().getType());
        Assertions.assertEquals(-200L, captor.getValue().getTokenDeltaTemp());
        Assertions.assertEquals(-50L, captor.getValue().getTokenDeltaPermanent());
        Assertions.assertEquals("AI_CHAT", captor.getValue().getSource());
    }

    @Test
    void consumeProjectTokensShouldRejectWhenBalanceNotEnough() {
        CreditAccount account = new CreditAccount();
        account.setUserId(1001L);
        account.setProjectKey("aisocialgame");
        account.setTempBalance(10L);
        account.setPermanentBalance(20L);
        when(creditLedgerEntryRepository.findByRequestId("consume-poor")).thenReturn(Optional.empty());
        when(creditAccountRepository.findForUpdate(1001L, "aisocialgame")).thenReturn(Optional.of(account));
        when(billingGrpcClient.getPublicPermanentTokens(1001L)).thenReturn(0L);

        ApiException ex = Assertions.assertThrows(ApiException.class, () ->
                projectCreditService.consumeProjectTokens(1001L, 50L, "AI_CHAT", Map.of(), "consume-poor")
        );

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        Assertions.assertEquals("专属积分不足，请先充值或兑换", ex.getMessage());
        verify(creditLedgerEntryRepository, never()).save(any());
    }
}
