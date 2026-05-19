package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.CheckinResult;
import com.aisocialgame.integration.grpc.dto.CheckinStatusResult;
import com.aisocialgame.integration.grpc.dto.LedgerEntrySnapshot;
import com.aisocialgame.integration.grpc.dto.PagedLedgerSnapshot;
import com.aisocialgame.integration.grpc.dto.PagedResult;
import com.aisocialgame.integration.grpc.dto.ExchangeHistorySnapshot;
import com.aisocialgame.integration.grpc.dto.RedeemResult;
import com.aisocialgame.integration.grpc.dto.RedemptionRecordSnapshot;
import com.aisocialgame.integration.grpc.dto.UsageRecordSnapshot;
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
import com.aisocialgame.service.credit.CreditLedgerService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectCreditService {
    private static final String CREDIT_TYPE_UNSPECIFIED = "CREDIT_TYPE_UNSPECIFIED";
    private static final String CREDIT_TYPE_TEMP = "CREDIT_TYPE_TEMP";
    private static final String CREDIT_TYPE_PERMANENT = "CREDIT_TYPE_PERMANENT";

    private static final String EXCHANGE_PENDING = "PENDING";
    private static final String EXCHANGE_SUCCESS = "SUCCESS";
    private static final String EXCHANGE_FAILED = "FAILED";

    private final CreditAccountRepository creditAccountRepository;
    private final CreditLedgerEntryRepository creditLedgerEntryRepository;
    private final CreditCheckinRecordRepository creditCheckinRecordRepository;
    private final CreditRedeemCodeRepository creditRedeemCodeRepository;
    private final CreditRedemptionRecordRepository creditRedemptionRecordRepository;
    private final CreditExchangeTransactionRepository creditExchangeTransactionRepository;
    private final BillingGrpcClient billingGrpcClient;
    private final AppProperties appProperties;
    private final CreditLedgerService creditLedgerService;
    private final TransactionTemplate transactionTemplate;

    public ProjectCreditService(CreditAccountRepository creditAccountRepository,
                                CreditLedgerEntryRepository creditLedgerEntryRepository,
                                CreditCheckinRecordRepository creditCheckinRecordRepository,
                                CreditRedeemCodeRepository creditRedeemCodeRepository,
                                CreditRedemptionRecordRepository creditRedemptionRecordRepository,
                                CreditExchangeTransactionRepository creditExchangeTransactionRepository,
                                BillingGrpcClient billingGrpcClient,
                                AppProperties appProperties,
                                CreditLedgerService creditLedgerService,
                                PlatformTransactionManager transactionManager) {
        this.creditAccountRepository = creditAccountRepository;
        this.creditLedgerEntryRepository = creditLedgerEntryRepository;
        this.creditCheckinRecordRepository = creditCheckinRecordRepository;
        this.creditRedeemCodeRepository = creditRedeemCodeRepository;
        this.creditRedemptionRecordRepository = creditRedemptionRecordRepository;
        this.creditExchangeTransactionRepository = creditExchangeTransactionRepository;
        this.billingGrpcClient = billingGrpcClient;
        this.appProperties = appProperties;
        this.creditLedgerService = creditLedgerService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BalanceSnapshot getBalance(long userId, long publicPermanentTokens) {
        Optional<CreditAccount> accountOpt = creditAccountRepository.findByUserIdAndProjectKey(userId, appProperties.getProjectKey());
        if (accountOpt.isEmpty()) {
            return new BalanceSnapshot(publicPermanentTokens, 0, 0, null);
        }
        CreditAccount account = accountOpt.get();
        Instant now = Instant.now();
        long tempBalance = account.getTempBalance();
        Instant tempExpiresAt = account.getTempExpiresAt();
        if (isTempExpired(account, now)) {
            tempBalance = 0;
            tempExpiresAt = null;
        }
        return new BalanceSnapshot(
                publicPermanentTokens,
                tempBalance,
                account.getPermanentBalance(),
                tempExpiresAt
        );
    }

    @Transactional
    public void ensureAccountInitialized(long userId) {
        if (userId <= 0) {
            return;
        }
        getOrCreateAccountForUpdate(userId, appProperties.getProjectKey());
    }

    @Transactional
    public CheckinResult checkin(long userId, long publicPermanentTokens) {
        String projectKey = appProperties.getProjectKey();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<CreditCheckinRecord> todayRecord = creditCheckinRecordRepository
                .findByUserIdAndProjectKeyAndCheckinDate(userId, projectKey, today);
        if (todayRecord.isPresent()) {
            return new CheckinResult(
                    true,
                    0,
                    true,
                    "",
                    getBalance(userId, publicPermanentTokens)
            );
        }

        CreditAccount account = getOrCreateAccountForUpdate(userId, projectKey);
        expireTempIfNeeded(account, publicPermanentTokens);

        long grantTokens = Math.max(0, appProperties.getCredit().getCheckinGrantTokens());
        account.setTempBalance(account.getTempBalance() + grantTokens);
        account.setTempExpiresAt(Instant.now().plusSeconds(Math.max(1, appProperties.getCredit().getTempExpiryDays()) * 86400L));
        creditAccountRepository.save(account);

        String requestId = projectKey + ":checkin:" + userId + ":" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
        creditLedgerService.insertLedgerEntry(
                requestId,
                userId,
                "CHECKIN",
                grantTokens,
                0,
                0,
                publicPermanentTokens,
                "CHECKIN",
                Map.of("checkinDate", today.toString()),
                null,
                account
        );

        CreditCheckinRecord record = new CreditCheckinRecord();
        record.setRequestId(requestId);
        record.setUserId(userId);
        record.setProjectKey(projectKey);
        record.setCheckinDate(today);
        record.setTokensGranted(grantTokens);
        creditCheckinRecordRepository.save(record);

        return new CheckinResult(true, grantTokens, false, "", toBalanceSnapshot(account, publicPermanentTokens));
    }

    public CheckinStatusResult getCheckinStatus(long userId) {
        String projectKey = appProperties.getProjectKey();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<CreditCheckinRecord> todayRecord = creditCheckinRecordRepository
                .findByUserIdAndProjectKeyAndCheckinDate(userId, projectKey, today);
        Optional<CreditCheckinRecord> latest = creditCheckinRecordRepository
                .findTopByUserIdAndProjectKeyOrderByCheckinDateDesc(userId, projectKey);
        Instant lastCheckinDate = latest.map(item -> item.getCheckinDate().atStartOfDay().toInstant(ZoneOffset.UTC)).orElse(null);
        long grantedToday = todayRecord.map(CreditCheckinRecord::getTokensGranted).orElse(0L);
        return new CheckinStatusResult(todayRecord.isPresent(), lastCheckinDate, grantedToday);
    }

    @Transactional
    public CreditRedeemCode createRedeemCode(String code,
                                             long tokens,
                                             String creditType,
                                             Integer maxRedemptions,
                                             Instant validFrom,
                                             Instant validUntil,
                                             Boolean active) {
        if (tokens <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "兑换积分必须大于 0");
        }
        if (maxRedemptions != null && maxRedemptions <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "最大兑换次数必须大于 0");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "失效时间必须晚于生效时间");
        }

        String normalizedType = normalizeCreditType(creditType);
        String normalizedCode = normalizeCode(code);
        if (StringUtils.hasText(normalizedCode)) {
            if (!normalizedCode.matches("[A-Z0-9\\-]{4,64}")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "兑换码格式不合法");
            }
            if (creditRedeemCodeRepository.findByCode(normalizedCode).isPresent()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "兑换码已存在");
            }
        } else {
            normalizedCode = generateUniqueRedeemCode();
        }

        CreditRedeemCode redeemCode = new CreditRedeemCode();
        redeemCode.setCode(normalizedCode);
        redeemCode.setCreditType(normalizedType);
        redeemCode.setTokens(tokens);
        redeemCode.setActive(active == null || active);
        redeemCode.setMaxRedemptions(maxRedemptions);
        redeemCode.setValidFrom(validFrom);
        redeemCode.setValidUntil(validUntil);
        redeemCode.setRedeemedCount(0);
        return creditRedeemCodeRepository.save(redeemCode);
    }

    @Transactional
    public RedeemResult redeemCode(long userId, String code, long publicPermanentTokens) {
        String projectKey = appProperties.getProjectKey();
        String normalizedCode = normalizeCode(code);
        if (!StringUtils.hasText(normalizedCode)) {
            return new RedeemResult(false, 0, CREDIT_TYPE_UNSPECIFIED, "兑换码不能为空", getBalance(userId, publicPermanentTokens));
        }

        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        long failedCount = creditRedemptionRecordRepository.countByUserIdAndProjectKeyAndSuccessFalseAndCreatedAtBetween(
                userId, projectKey, dayStart, dayEnd
        );
        if (failedCount >= appProperties.getCredit().getRedeemFailureLimitPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "今日兑换失败次数过多，请稍后再试");
        }

        if (creditRedemptionRecordRepository.existsByUserIdAndProjectKeyAndCodeAndSuccessTrue(userId, projectKey, normalizedCode)) {
            return new RedeemResult(false, 0, CREDIT_TYPE_UNSPECIFIED, "兑换码已使用", getBalance(userId, publicPermanentTokens));
        }

        Optional<CreditRedeemCode> codeOpt = creditRedeemCodeRepository.findForUpdate(normalizedCode);
        if (codeOpt.isEmpty()) {
            recordRedeemFailure(userId, normalizedCode, "兑换码无效");
            return new RedeemResult(false, 0, CREDIT_TYPE_UNSPECIFIED, "兑换码无效", getBalance(userId, publicPermanentTokens));
        }

        CreditRedeemCode redeemCode = codeOpt.get();
        String validationError = validateRedeemCode(redeemCode);
        if (validationError != null) {
            recordRedeemFailure(userId, normalizedCode, validationError);
            return new RedeemResult(false, 0, CREDIT_TYPE_UNSPECIFIED, validationError, getBalance(userId, publicPermanentTokens));
        }

        CreditAccount account = getOrCreateAccountForUpdate(userId, projectKey);
        expireTempIfNeeded(account, publicPermanentTokens);

        long deltaTemp = 0;
        long deltaPermanent = 0;
        if (CREDIT_TYPE_TEMP.equals(redeemCode.getCreditType())) {
            deltaTemp = redeemCode.getTokens();
            account.setTempBalance(account.getTempBalance() + deltaTemp);
            account.setTempExpiresAt(Instant.now().plusSeconds(Math.max(1, appProperties.getCredit().getTempExpiryDays()) * 86400L));
        } else {
            deltaPermanent = redeemCode.getTokens();
            account.setPermanentBalance(account.getPermanentBalance() + deltaPermanent);
        }
        creditAccountRepository.save(account);

        String requestId = projectKey + ":redeem:" + userId + ":" + UUID.randomUUID();
        creditLedgerService.insertLedgerEntry(
                requestId,
                userId,
                "REDEEM",
                deltaTemp,
                deltaPermanent,
                0,
                publicPermanentTokens,
                "REDEEM_CODE",
                Map.of("code", normalizedCode),
                null,
                account
        );

        redeemCode.setRedeemedCount(redeemCode.getRedeemedCount() + 1);
        creditRedeemCodeRepository.save(redeemCode);

        CreditRedemptionRecord redemptionRecord = new CreditRedemptionRecord();
        redemptionRecord.setRequestId(requestId);
        redemptionRecord.setUserId(userId);
        redemptionRecord.setProjectKey(projectKey);
        redemptionRecord.setCode(normalizedCode);
        redemptionRecord.setCreditType(redeemCode.getCreditType());
        redemptionRecord.setTokensGranted(redeemCode.getTokens());
        redemptionRecord.setSuccess(true);
        redemptionRecord.setRedeemedAt(Instant.now());
        creditRedemptionRecordRepository.save(redemptionRecord);

        return new RedeemResult(
                true,
                redeemCode.getTokens(),
                redeemCode.getCreditType(),
                "",
                toBalanceSnapshot(account, publicPermanentTokens)
        );
    }

    public PagedResult<RedemptionRecordSnapshot> getRedemptionHistory(long userId, int page, int size) {
        String projectKey = appProperties.getProjectKey();
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        var pageData = creditRedemptionRecordRepository.findByUserIdAndProjectKeyAndSuccessTrueOrderByIdDesc(
                userId,
                projectKey,
                PageRequest.of(normalizedPage - 1, normalizedSize)
        );
        List<RedemptionRecordSnapshot> records = pageData.getContent().stream()
                .map(item -> new RedemptionRecordSnapshot(
                        item.getId(),
                        item.getCode(),
                        item.getTokensGranted(),
                        item.getCreditType(),
                        item.getProjectKey(),
                        item.getRedeemedAt()
                ))
                .toList();
        return new PagedResult<>(normalizedPage, normalizedSize, pageData.getTotalElements(), records);
    }

    public PagedResult<UsageRecordSnapshot> listUsageRecords(long userId, int page, int size) {
        return creditLedgerService.listUsageRecords(userId, page, size);
    }

    public PagedResult<LedgerEntrySnapshot> listLedgerEntries(long userId, int page, int size) {
        PagedLedgerSnapshot snapshot = listLedgerEntriesForAdmin(userId, page, size);
        return new PagedResult<>(snapshot.page(), snapshot.size(), snapshot.total(), snapshot.entries());
    }

    public PagedLedgerSnapshot listLedgerEntriesForAdmin(long userId, int page, int size) {
        return creditLedgerService.listLedgerEntriesForAdmin(userId, page, size);
    }

    @Transactional
    public BalanceSnapshot consumeProjectTokens(long userId,
                                                long billedTokens,
                                                String source,
                                                Map<String, String> metadata,
                                                String requestId) {
        if (billedTokens <= 0) {
            return getBalance(userId, safeGetPublicTokens(userId));
        }

        String normalizedRequestId = normalizeRequestId(requestId, "consume", userId);
        if (creditLedgerEntryRepository.findByRequestId(normalizedRequestId).isPresent()) {
            return getBalance(userId, safeGetPublicTokens(userId));
        }

        long publicTokens = safeGetPublicTokens(userId);
        CreditAccount account = getOrCreateAccountForUpdate(userId, appProperties.getProjectKey());
        expireTempIfNeeded(account, publicTokens);

        long available = Math.max(0, account.getTempBalance()) + Math.max(0, account.getPermanentBalance());
        if (available < billedTokens) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "专属积分不足，请先充值或兑换");
        }

        long consumeTemp = Math.min(account.getTempBalance(), billedTokens);
        long consumePermanent = billedTokens - consumeTemp;
        account.setTempBalance(account.getTempBalance() - consumeTemp);
        account.setPermanentBalance(account.getPermanentBalance() - consumePermanent);
        if (account.getTempBalance() == 0) {
            account.setTempExpiresAt(null);
        }
        creditAccountRepository.save(account);

        Map<String, String> normalizedMetadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        normalizedMetadata.put("billedTokens", String.valueOf(billedTokens));
        creditLedgerService.insertLedgerEntry(
                normalizedRequestId,
                userId,
                "CONSUME",
                -consumeTemp,
                -consumePermanent,
                0,
                publicTokens,
                StringUtils.hasText(source) ? source.trim() : "AI_CONSUME",
                normalizedMetadata,
                null,
                account
        );
        return toBalanceSnapshot(account, publicTokens);
    }

    public CreditExchangeResult exchangePublicToProject(long userId, long tokens, String requestId) {
        if (tokens <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "兑换数量必须大于 0");
        }
        String normalizedRequestId = normalizeRequestId(requestId, "exchange", userId);
        String projectKey = appProperties.getProjectKey();
        long publicBefore = safeGetPublicTokens(userId);

        CreditExchangeTransaction pending = transactionTemplate.execute(status -> {
            Optional<CreditExchangeTransaction> existing = creditExchangeTransactionRepository.findByRequestId(normalizedRequestId);
            if (existing.isPresent()) {
                CreditExchangeTransaction txn = existing.get();
                if (EXCHANGE_SUCCESS.equals(txn.getStatus())) {
                    return txn;
                }
                if (EXCHANGE_PENDING.equals(txn.getStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "请求处理中，请勿重复提交");
                }
                throw new ApiException(HttpStatus.BAD_REQUEST, "该 requestId 已失败，请使用新的 requestId 重试");
            }

            LocalDateTime start = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
            LocalDateTime end = start.plusDays(1);
            long dailyUsed = creditExchangeTransactionRepository.sumSuccessTokensBetween(userId, projectKey, start, end);
            long dailyLimit = Math.max(1, appProperties.getCredit().getExchangeDailyLimit());
            if (dailyUsed + tokens > dailyLimit) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "超出当日可兑换上限");
            }

            CreditAccount account = getOrCreateAccountForUpdate(userId, projectKey);
            expireTempIfNeeded(account, publicBefore);
            long projectPermanentBefore = account.getPermanentBalance();
            CreditExchangeTransaction txn = new CreditExchangeTransaction();
            txn.setRequestId(normalizedRequestId);
            txn.setUserId(userId);
            txn.setProjectKey(projectKey);
            txn.setPublicTokens(tokens);
            txn.setProjectTokens(tokens);
            txn.setStatus(EXCHANGE_PENDING);
            txn.setRetriable(true);
            txn.setPublicBefore(publicBefore);
            txn.setPublicAfter(publicBefore);
            txn.setProjectPermanentBefore(projectPermanentBefore);
            txn.setProjectPermanentAfter(projectPermanentBefore);
            return creditExchangeTransactionRepository.save(txn);
        });

        if (pending != null && EXCHANGE_SUCCESS.equals(pending.getStatus())) {
            long publicTokens = safeGetPublicTokens(userId);
            return new CreditExchangeResult(normalizedRequestId, pending.getProjectTokens(), getBalance(userId, publicTokens));
        }

        BalanceSnapshot converted;
        try {
            converted = billingGrpcClient.convertPublicToProject(normalizedRequestId, projectKey, userId, tokens);
        } catch (Exception ex) {
            markExchangeFailed(normalizedRequestId, ex);
            throw ex;
        }

        long publicAfterConvert = converted.publicPermanentTokens();
        return transactionTemplate.execute(status -> {
            CreditExchangeTransaction txn = creditExchangeTransactionRepository.findByRequestId(normalizedRequestId)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "兑换事务不存在"));
            if (EXCHANGE_SUCCESS.equals(txn.getStatus())) {
                return new CreditExchangeResult(normalizedRequestId, txn.getProjectTokens(), getBalance(userId, publicAfterConvert));
            }
            CreditAccount account = getOrCreateAccountForUpdate(userId, projectKey);
            expireTempIfNeeded(account, publicAfterConvert);
            account.setPermanentBalance(account.getPermanentBalance() + tokens);
            creditAccountRepository.save(account);

            creditLedgerService.insertLedgerEntry(
                    normalizedRequestId,
                    userId,
                    "EXCHANGE_IN",
                    0,
                    tokens,
                    -tokens,
                    publicAfterConvert,
                    "PUBLIC_TO_PROJECT",
                    Map.of("ratio", "1:1"),
                    null,
                    account
            );

            txn.setStatus(EXCHANGE_SUCCESS);
            txn.setRetriable(false);
            txn.setPublicAfter(publicAfterConvert);
            txn.setProjectPermanentAfter(account.getPermanentBalance());
            creditExchangeTransactionRepository.save(txn);
            return new CreditExchangeResult(normalizedRequestId, tokens, toBalanceSnapshot(account, publicAfterConvert));
        });
    }

    private void markExchangeFailed(String requestId, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> creditExchangeTransactionRepository.findByRequestId(requestId).ifPresent(txn -> {
            txn.setStatus(EXCHANGE_FAILED);
            txn.setRetriable(true);
            txn.setFailReason(truncate(ex.getMessage(), 255));
            creditExchangeTransactionRepository.save(txn);
        }));
    }

    public PagedResult<ExchangeHistorySnapshot> listExchangeHistory(long userId, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        var pageData = creditExchangeTransactionRepository.findByUserIdAndProjectKeyAndStatusOrderByIdDesc(
                userId,
                appProperties.getProjectKey(),
                EXCHANGE_SUCCESS,
                PageRequest.of(normalizedPage - 1, normalizedSize)
        );
        List<ExchangeHistorySnapshot> records = pageData.getContent().stream()
                .map(item -> new ExchangeHistorySnapshot(
                        item.getId(),
                        item.getRequestId(),
                        item.getProjectTokens(),
                        item.getPublicBefore(),
                        item.getPublicAfter(),
                        item.getProjectPermanentBefore(),
                        item.getProjectPermanentAfter(),
                        item.getCreatedAt() == null ? null : item.getCreatedAt().toInstant(ZoneOffset.UTC)
                ))
                .toList();
        return new PagedResult<>(normalizedPage, normalizedSize, pageData.getTotalElements(), records);
    }

    @Transactional
    public BalanceSnapshot adjustBalance(long userId, long deltaTemp, long deltaPermanent, String reason, String operator, String requestId) {
        if (deltaTemp == 0 && deltaPermanent == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "调整值不能同时为 0");
        }
        String projectKey = appProperties.getProjectKey();
        long publicTokens = safeGetPublicTokens(userId);
        CreditAccount account = getOrCreateAccountForUpdate(userId, projectKey);
        expireTempIfNeeded(account, publicTokens);

        long nextTemp = account.getTempBalance() + deltaTemp;
        long nextPermanent = account.getPermanentBalance() + deltaPermanent;
        if (nextTemp < 0 || nextPermanent < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "调整后积分不能为负数");
        }
        account.setTempBalance(nextTemp);
        account.setPermanentBalance(nextPermanent);
        if (deltaTemp > 0) {
            account.setTempExpiresAt(Instant.now().plusSeconds(Math.max(1, appProperties.getCredit().getTempExpiryDays()) * 86400L));
        }
        if (nextTemp == 0) {
            account.setTempExpiresAt(null);
        }
        creditAccountRepository.save(account);

        String normalizedRequestId = normalizeRequestId(requestId, "admin-adjust", userId);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("reason", StringUtils.hasText(reason) ? reason.trim() : "admin_adjust");
        metadata.put("operator", StringUtils.hasText(operator) ? operator.trim() : "admin");
        creditLedgerService.insertLedgerEntry(
                normalizedRequestId,
                userId,
                "ADJUST",
                deltaTemp,
                deltaPermanent,
                0,
                publicTokens,
                "ADMIN",
                metadata,
                null,
                account
        );
        return toBalanceSnapshot(account, publicTokens);
    }

    @Transactional
    public BalanceSnapshot reverseByRequestId(long userId, String originalRequestId, String reason, String operator) {
        if (!StringUtils.hasText(originalRequestId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "原始 requestId 不能为空");
        }
        CreditLedgerEntry original = creditLedgerEntryRepository.findByRequestId(originalRequestId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "原始流水不存在"));
        if (original.getUserId() != userId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "原始流水用户不匹配");
        }
        if ("REVERSAL".equalsIgnoreCase(original.getType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "冲正流水不允许再次冲正");
        }
        if (creditLedgerEntryRepository.existsByRelatedEntryId(original.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该流水已冲正");
        }

        long publicTokens = safeGetPublicTokens(userId);
        CreditAccount account = getOrCreateAccountForUpdate(userId, appProperties.getProjectKey());
        expireTempIfNeeded(account, publicTokens);

        long deltaTemp = -original.getTokenDeltaTemp();
        long deltaPermanent = -original.getTokenDeltaPermanent();
        long nextTemp = account.getTempBalance() + deltaTemp;
        long nextPermanent = account.getPermanentBalance() + deltaPermanent;
        if (nextTemp < 0 || nextPermanent < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "冲正后积分将为负数，请先补发再冲正");
        }
        account.setTempBalance(nextTemp);
        account.setPermanentBalance(nextPermanent);
        if (nextTemp == 0) {
            account.setTempExpiresAt(null);
        }
        creditAccountRepository.save(account);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("reason", StringUtils.hasText(reason) ? reason.trim() : "admin_reversal");
        metadata.put("operator", StringUtils.hasText(operator) ? operator.trim() : "admin");
        metadata.put("originalRequestId", original.getRequestId());
        creditLedgerService.insertLedgerEntry(
                appProperties.getProjectKey() + ":reversal:" + userId + ":" + UUID.randomUUID(),
                userId,
                "REVERSAL",
                deltaTemp,
                deltaPermanent,
                0,
                publicTokens,
                "ADMIN_REVERSE",
                metadata,
                original.getId(),
                account
        );
        return toBalanceSnapshot(account, publicTokens);
    }

    @Transactional
    public BalanceSnapshot migrateFromPayServiceSnapshot(long userId,
                                                         long projectTempTokens,
                                                         long projectPermanentTokens,
                                                         long publicPermanentTokens,
                                                         String operator) {
        String requestId = appProperties.getProjectKey() + ":migrate:" + userId;
        if (creditLedgerEntryRepository.findByRequestId(requestId).isPresent()) {
            return getBalance(userId, publicPermanentTokens);
        }

        CreditAccount account = getOrCreateAccountForUpdate(userId, appProperties.getProjectKey());
        long deltaTemp = projectTempTokens - account.getTempBalance();
        long deltaPermanent = projectPermanentTokens - account.getPermanentBalance();
        account.setTempBalance(Math.max(0, projectTempTokens));
        account.setPermanentBalance(Math.max(0, projectPermanentTokens));
        if (projectTempTokens > 0) {
            account.setTempExpiresAt(Instant.now().plusSeconds(Math.max(1, appProperties.getCredit().getTempExpiryDays()) * 86400L));
        } else {
            account.setTempExpiresAt(null);
        }
        creditAccountRepository.save(account);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("operator", StringUtils.hasText(operator) ? operator.trim() : "admin");
        metadata.put("source", "payservice_snapshot");
        creditLedgerService.insertLedgerEntry(
                requestId,
                userId,
                "MIGRATION_INIT",
                deltaTemp,
                deltaPermanent,
                0,
                publicPermanentTokens,
                "MIGRATION",
                metadata,
                null,
                account
        );
        return toBalanceSnapshot(account, publicPermanentTokens);
    }

    private String validateRedeemCode(CreditRedeemCode redeemCode) {
        if (!redeemCode.isActive()) {
            return "兑换码已停用";
        }
        Instant now = Instant.now();
        if (redeemCode.getValidFrom() != null && now.isBefore(redeemCode.getValidFrom())) {
            return "兑换码尚未生效";
        }
        if (redeemCode.getValidUntil() != null && now.isAfter(redeemCode.getValidUntil())) {
            return "兑换码已过期";
        }
        Integer maxRedemptions = redeemCode.getMaxRedemptions();
        if (maxRedemptions != null && maxRedemptions > 0 && redeemCode.getRedeemedCount() >= maxRedemptions) {
            return "兑换码已被领完";
        }
        return null;
    }

    private void recordRedeemFailure(long userId, String code, String message) {
        CreditRedemptionRecord failure = new CreditRedemptionRecord();
        failure.setRequestId(appProperties.getProjectKey() + ":redeem-fail:" + userId + ":" + UUID.randomUUID());
        failure.setUserId(userId);
        failure.setProjectKey(appProperties.getProjectKey());
        failure.setCode(code);
        failure.setCreditType(CREDIT_TYPE_UNSPECIFIED);
        failure.setTokensGranted(0);
        failure.setSuccess(false);
        failure.setErrorMessage(truncate(message, 255));
        creditRedemptionRecordRepository.save(failure);
    }

    private CreditAccount getOrCreateAccountForUpdate(long userId, String projectKey) {
        return creditAccountRepository.findForUpdate(userId, projectKey)
                .orElseGet(() -> {
                    CreditAccount account = new CreditAccount();
                    account.setUserId(userId);
                    account.setProjectKey(projectKey);
                    account.setTempBalance(0);
                    account.setPermanentBalance(0);
                    return creditAccountRepository.save(account);
                });
    }

    private void expireTempIfNeeded(CreditAccount account, long publicPermanentTokens) {
        Instant now = Instant.now();
        if (!isTempExpired(account, now)) {
            return;
        }
        long expired = account.getTempBalance();
        account.setTempBalance(0);
        account.setTempExpiresAt(null);
        creditAccountRepository.save(account);
        creditLedgerService.insertLedgerEntry(
                appProperties.getProjectKey() + ":expire:" + account.getUserId() + ":" + UUID.randomUUID(),
                account.getUserId(),
                "EXPIRE",
                -expired,
                0,
                0,
                publicPermanentTokens,
                "TEMP_EXPIRE",
                Map.of("expiredAt", now.toString()),
                null,
                account
        );
    }

    private boolean isTempExpired(CreditAccount account, Instant now) {
        return account.getTempBalance() > 0
                && account.getTempExpiresAt() != null
                && now.isAfter(account.getTempExpiresAt());
    }

    private BalanceSnapshot toBalanceSnapshot(CreditAccount account, long publicPermanentTokens) {
        long temp = account.getTempBalance();
        Instant expiresAt = account.getTempExpiresAt();
        if (isTempExpired(account, Instant.now())) {
            temp = 0;
            expiresAt = null;
        }
        return new BalanceSnapshot(publicPermanentTokens, temp, account.getPermanentBalance(), expiresAt);
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return code.trim().toUpperCase();
    }

    private String normalizeCreditType(String creditType) {
        if (!StringUtils.hasText(creditType)) {
            return CREDIT_TYPE_PERMANENT;
        }
        String normalized = creditType.trim().toUpperCase();
        if (CREDIT_TYPE_TEMP.equals(normalized) || CREDIT_TYPE_PERMANENT.equals(normalized)) {
            return normalized;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "creditType 不支持");
    }

    private String generateUniqueRedeemCode() {
        String prefix = appProperties.getProjectKey();
        if (!StringUtils.hasText(prefix)) {
            prefix = "ASG";
        }
        prefix = prefix.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (!StringUtils.hasText(prefix)) {
            prefix = "ASG";
        }
        if (prefix.length() > 8) {
            prefix = prefix.substring(0, 8);
        }

        for (int i = 0; i < 10; i++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            String candidate = prefix + "-" + suffix;
            if (creditRedeemCodeRepository.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "生成兑换码失败，请重试");
    }

    private String normalizeRequestId(String requestId, String action, long userId) {
        if (StringUtils.hasText(requestId)) {
            return requestId.trim();
        }
        return appProperties.getProjectKey() + ":" + action + ":" + userId + ":" + UUID.randomUUID();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private long safeGetPublicTokens(long userId) {
        try {
            return billingGrpcClient.getPublicPermanentTokens(userId);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
