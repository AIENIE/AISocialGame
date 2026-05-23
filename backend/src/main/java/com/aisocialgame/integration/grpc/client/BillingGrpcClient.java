package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.BillingGrpcAuthClientInterceptor;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.CheckinResult;
import com.aisocialgame.integration.grpc.dto.CheckinStatusResult;
import com.aisocialgame.integration.grpc.dto.LedgerEntrySnapshot;
import com.aisocialgame.integration.grpc.dto.PagedResult;
import com.aisocialgame.integration.grpc.dto.PagedLedgerSnapshot;
import com.aisocialgame.integration.grpc.dto.RedeemResult;
import com.aisocialgame.integration.grpc.dto.RedemptionRecordSnapshot;
import com.aisocialgame.integration.grpc.dto.UsageRecordSnapshot;
import fireflychat.billing.v1.BillingCheckinServiceGrpc;
import fireflychat.billing.v1.BillingConversionServiceGrpc;
import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.BillingQueryServiceGrpc;
import fireflychat.billing.v1.BillingRedeemCodeServiceGrpc;
import fireflychat.billing.v1.BillingOnboardingServiceGrpc;
import fireflychat.billing.v1.CheckinRequest;
import fireflychat.billing.v1.ConvertPublicToProjectRequest;
import fireflychat.billing.v1.EnsureUserInitializedRequest;
import fireflychat.billing.v1.GetCheckinStatusRequest;
import fireflychat.billing.v1.GetProjectBalanceRequest;
import fireflychat.billing.v1.GetPublicBalanceRequest;
import fireflychat.billing.v1.GetRedemptionHistoryRequest;
import fireflychat.billing.v1.ListLedgerEntriesRequest;
import fireflychat.billing.v1.ListUsageRecordsRequest;
import fireflychat.billing.v1.RedeemCodeRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BillingGrpcClient {

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub balanceStub;

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingQueryServiceGrpc.BillingQueryServiceBlockingStub queryStub;

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingCheckinServiceGrpc.BillingCheckinServiceBlockingStub checkinStub;

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingRedeemCodeServiceGrpc.BillingRedeemCodeServiceBlockingStub redeemCodeStub;

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingConversionServiceGrpc.BillingConversionServiceBlockingStub conversionStub;

    @GrpcClient(value = "billing", interceptors = BillingGrpcAuthClientInterceptor.class)
    private BillingOnboardingServiceGrpc.BillingOnboardingServiceBlockingStub onboardingStub;

    public BalanceSnapshot getBalance(String projectKey, long userId) {
        try {
            long publicTokens = getPublicPermanentTokens(userId);
            var projectResponse = balanceStub.getProjectBalance(GetProjectBalanceRequest.newBuilder()
                    .setProjectKey(projectKey)
                    .setUserId(userId)
                    .build());
            var projectBalance = projectResponse.getBalance();
            return new BalanceSnapshot(
                    publicTokens,
                    preferCredits(projectBalance.getTempCredits(), projectBalance.getTempTokens()),
                    preferCredits(projectBalance.getPermanentCredits(), projectBalance.getPermanentTokens()),
                    GrpcTimeMapper.toInstant(projectBalance.getTempExpiresAt())
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public BalanceSnapshot getProjectBalance(String projectKey, long userId) {
        try {
            var projectResponse = balanceStub.getProjectBalance(GetProjectBalanceRequest.newBuilder()
                    .setProjectKey(projectKey)
                    .setUserId(userId)
                    .build());
            var projectBalance = projectResponse.getBalance();
            return new BalanceSnapshot(
                    0,
                    preferCredits(projectBalance.getTempCredits(), projectBalance.getTempTokens()),
                    preferCredits(projectBalance.getPermanentCredits(), projectBalance.getPermanentTokens()),
                    GrpcTimeMapper.toInstant(projectBalance.getTempExpiresAt())
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public long getPublicPermanentTokens(long userId) {
        try {
            var publicResponse = balanceStub.getPublicBalance(GetPublicBalanceRequest.newBuilder()
                    .setUserId(userId)
                    .build());
            return preferCredits(publicResponse.getPublicPermanentCredits(), publicResponse.getPublicPermanentTokens());
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public CheckinResult checkin(String requestId, String projectKey, long userId) {
        try {
            var response = checkinStub.checkin(CheckinRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .build());
            long publicTokens = getPublicTokens(userId);
            BalanceSnapshot balance = toBalanceSnapshot(publicTokens, response.getProjectBalance());
            return new CheckinResult(
                    response.getSuccess(),
                    preferCredits(response.getCreditsGranted(), response.getTokensGranted()),
                    response.getAlreadyCheckedIn(),
                    response.getErrorMessage(),
                    balance
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public CheckinStatusResult getCheckinStatus(String projectKey, long userId) {
        try {
            var response = checkinStub.getCheckinStatus(GetCheckinStatusRequest.newBuilder()
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .build());
            return new CheckinStatusResult(
                    response.getCheckedInToday(),
                    GrpcTimeMapper.toInstant(response.getLastCheckinDate()),
                    preferCredits(response.getCreditsGrantedToday(), response.getTokensGrantedToday())
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public PagedResult<UsageRecordSnapshot> listUsageRecords(String projectKey, long userId, int page, int size) {
        try {
            var response = queryStub.listUsageRecords(ListUsageRecordsRequest.newBuilder()
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setPage(page)
                    .setSize(size)
                    .build());
            List<UsageRecordSnapshot> records = response.getRecordsList().stream()
                    .map(record -> new UsageRecordSnapshot(
                            record.getId(),
                            record.getRequestId(),
                            record.getProjectKey(),
                            record.getModelKey(),
                            preferCredits(record.getInputCredits(), record.getPromptTokens()),
                            preferCredits(record.getOutputCredits(), record.getCompletionTokens()),
                            preferCredits(record.getBilledCredits(), record.getBilledTokens()),
                            GrpcTimeMapper.toInstant(record.getCreatedAt())
                    ))
                    .toList();
            var pageInfo = response.getPageInfo();
            return new PagedResult<>(pageInfo.getPage(), pageInfo.getSize(), pageInfo.getTotal(), records);
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public PagedLedgerSnapshot listLedgerEntries(long userId, String projectKey, int page, int size) {
        try {
            var response = queryStub.listLedgerEntries(ListLedgerEntriesRequest.newBuilder()
                    .setUserId(userId)
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setPage(page)
                    .setSize(size)
                    .build());
            List<LedgerEntrySnapshot> entries = response.getEntriesList().stream()
                    .map(entry -> new LedgerEntrySnapshot(
                            entry.getId(),
                            entry.getRequestId(),
                            entry.getProjectKey(),
                            entry.getType(),
                            preferCredits(entry.getCreditDeltaTemp(), entry.getTokenDeltaTemp()),
                            preferCredits(entry.getCreditDeltaPermanent(), entry.getTokenDeltaPermanent()),
                            preferCredits(entry.getCreditDeltaPublic(), entry.getTokenDeltaPublic()),
                            preferCredits(entry.getBalanceTempCredits(), entry.getBalanceTemp()),
                            preferCredits(entry.getBalancePermanentCredits(), entry.getBalancePermanent()),
                            preferCredits(entry.getBalancePublicCredits(), entry.getBalancePublic()),
                            entry.getSource(),
                            GrpcTimeMapper.toInstant(entry.getCreatedAt()),
                            entry.getMetadataMap()
                    ))
                    .toList();
            var info = response.getPageInfo();
            return new PagedLedgerSnapshot(info.getPage(), info.getSize(), info.getTotal(), entries);
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public PagedResult<LedgerEntrySnapshot> listLedgerEntriesForWallet(long userId, String projectKey, int page, int size) {
        PagedLedgerSnapshot snapshot = listLedgerEntries(userId, projectKey, page, size);
        return new PagedResult<>(snapshot.page(), snapshot.size(), snapshot.total(), snapshot.entries());
    }

    public BalanceSnapshot convertPublicToProject(String requestId, String projectKey, long userId, long credits) {
        try {
            var response = conversionStub.convertPublicToProject(ConvertPublicToProjectRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setCredits(credits)
                    .setTokens(credits)
                    .build());
            return toBalanceSnapshot(
                    preferCredits(response.getPublicPermanentCredits(), response.getPublicPermanentTokens()),
                    response.getProjectBalance()
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public void ensureUserInitialized(String requestId, String projectKey, long userId) {
        try {
            var response = onboardingStub.ensureUserInitialized(EnsureUserInitializedRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .build());
            if (!response.getSuccess()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, normalizeError(response.getErrorMessage(), "计费账户初始化失败"));
            }
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public RedeemResult redeemCode(String requestId, String projectKey, long userId, String code) {
        try {
            var response = redeemCodeStub.redeemCode(RedeemCodeRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setCode(code == null ? "" : code.trim())
                    .build());
            BalanceSnapshot balance = toBalanceSnapshot(
                    preferCredits(response.getPublicPermanentCredits(), response.getPublicPermanentTokens()),
                    response.getProjectBalance()
            );
            return new RedeemResult(
                    response.getSuccess(),
                    preferCredits(response.getCreditsGranted(), response.getTokensGranted()),
                    response.getCreditType().name(),
                    response.getErrorMessage(),
                    balance
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public PagedResult<RedemptionRecordSnapshot> getRedemptionHistory(String projectKey, long userId, int page, int size) {
        try {
            var response = redeemCodeStub.getRedemptionHistory(GetRedemptionHistoryRequest.newBuilder()
                    .setProjectKey(projectKey == null ? "" : projectKey)
                    .setUserId(userId)
                    .setPage(page)
                    .setSize(size)
                    .build());
            List<RedemptionRecordSnapshot> records = response.getRedemptionsList().stream()
                    .map(item -> new RedemptionRecordSnapshot(
                            item.getId(),
                            item.getCode(),
                            preferCredits(item.getCreditsGranted(), item.getTokensGranted()),
                            item.getCreditType().name(),
                            item.getProjectKey(),
                            GrpcTimeMapper.toInstant(item.getRedeemedAt())
                    ))
                    .toList();
            return new PagedResult<>(response.getPage(), response.getSize(), response.getTotal(), records);
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    private ApiException toApiException(StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        HttpStatus status = switch (code) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE, DEADLINE_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        String message = ex.getStatus().getDescription();
        if (message == null || message.isBlank()) {
            message = "积分服务调用失败";
        }
        return new ApiException(status, message);
    }

    private long getPublicTokens(long userId) {
        return getPublicPermanentTokens(userId);
    }

    private String normalizeError(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private BalanceSnapshot toBalanceSnapshot(long publicPermanentTokens, fireflychat.billing.v1.ProjectBalance projectBalance) {
        if (projectBalance == null) {
            return new BalanceSnapshot(publicPermanentTokens, 0, 0, null);
        }
        return new BalanceSnapshot(
                publicPermanentTokens,
                preferCredits(projectBalance.getTempCredits(), projectBalance.getTempTokens()),
                preferCredits(projectBalance.getPermanentCredits(), projectBalance.getPermanentTokens()),
                GrpcTimeMapper.toInstant(projectBalance.getTempExpiresAt())
        );
    }

    private long preferCredits(long credits, long tokensMirror) {
        return credits != 0 ? credits : tokensMirror;
    }
}
