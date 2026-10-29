package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import fireflychat.billing.v1.BillingConversionServiceGrpc;
import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.BillingOnboardingServiceGrpc;
import fireflychat.billing.v1.ConvertPublicToProjectRequest;
import fireflychat.billing.v1.EnsureUserInitializedRequest;
import fireflychat.billing.v1.GetPublicBalanceRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;


@Component
public class BillingGrpcClient {

    @Autowired
    private BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub balanceStub;

    @Autowired
    private BillingConversionServiceGrpc.BillingConversionServiceBlockingStub conversionStub;

    @Autowired
    private BillingOnboardingServiceGrpc.BillingOnboardingServiceBlockingStub onboardingStub;

    public long getPublicPermanentTokens(long userId) {
        try {
            var publicResponse = balanceStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).getPublicBalance(GetPublicBalanceRequest.newBuilder()
                    .setUserId(userId)
                    .build());
            return publicResponse.getPublicPermanentCredits();
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public BalanceSnapshot convertPublicToProject(String requestId, String projectKey, long userId, long credits) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        try {
            var response = conversionStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).convertPublicToProject(ConvertPublicToProjectRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey))
                    .setUserId(userId)
                    .setCredits(credits)
                    .build());
            if (!response.hasProjectBalance()) throw new ApiException(HttpStatus.BAD_GATEWAY, "兑换响应未提供项目余额");
            return toBalanceSnapshot(
                    response.getPublicPermanentCredits(),
                    response.getProjectBalance()
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public void ensureUserInitialized(String requestId, String projectKey, long userId) {
        AppProperties.requireCanonicalProjectKey(projectKey);
        try {
            var response = onboardingStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).ensureUserInitialized(EnsureUserInitializedRequest.newBuilder()
                    .setRequestId(requestId == null ? "" : requestId)
                    .setProjectKey(AppProperties.requireCanonicalProjectKey(projectKey))
                    .setUserId(userId)
                    .build());
            if (!response.getSuccess()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, normalizeError(response.getErrorMessage(), "计费账户初始化失败"));
            }
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

    private String normalizeError(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private BalanceSnapshot toBalanceSnapshot(long publicPermanentTokens, fireflychat.billing.v1.ProjectBalance projectBalance) {
        return new BalanceSnapshot(
                publicPermanentTokens,
                projectBalance.getTempCredits(),
                projectBalance.getPermanentCredits(),
                GrpcTimeMapper.toInstant(projectBalance.getTempExpiresAt())
        );
    }

}
