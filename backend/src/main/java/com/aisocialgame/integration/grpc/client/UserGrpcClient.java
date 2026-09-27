package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.UserGrpcAuthClientInterceptor;
import com.aisocialgame.integration.grpc.dto.BanStatusSnapshot;
import com.aisocialgame.integration.grpc.dto.ExternalUserProfile;
import fireflychat.user.v1.BanType;
import fireflychat.user.v1.BanUserRequest;
import fireflychat.user.v1.GetBanStatusRequest;
import fireflychat.user.v1.GetUserBasicRequest;
import fireflychat.user.v1.UnbanUserRequest;
import fireflychat.user.v1.UserAuthServiceGrpc;
import fireflychat.user.v1.UserBanServiceGrpc;
import fireflychat.user.v1.UserBasic;
import fireflychat.user.v1.UserDirectoryServiceGrpc;
import fireflychat.user.v1.ValidateSessionRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class UserGrpcClient {

    @Autowired
    private UserAuthServiceGrpc.UserAuthServiceBlockingStub userAuthStub;

    @Autowired
    private UserDirectoryServiceGrpc.UserDirectoryServiceBlockingStub userDirectoryStub;

    @Autowired
    private UserBanServiceGrpc.UserBanServiceBlockingStub userBanStub;

    public ExternalUserProfile validateSession(long userId, String sessionId) {
        try {
            var response = userAuthStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).validateSession(ValidateSessionRequest.newBuilder()
                    .setUserId(userId)
                    .setSessionId(normalize(sessionId))
                    .build());
            if (!response.getValid()) {
                return null;
            }
            return response.hasUser() ? toProfile(response.getUser()) : null;
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public ExternalUserProfile getUserBasic(long userId) {
        try {
            var response = userDirectoryStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).getUserBasic(GetUserBasicRequest.newBuilder().setUserId(userId).build());
            if (!response.hasUser()) {
                return null;
            }
            return toProfile(response.getUser());
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public BanStatusSnapshot getBanStatus(long userId) {
        try {
            var response = userBanStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).getBanStatus(GetBanStatusRequest.newBuilder().setUserId(userId).build());
            return new BanStatusSnapshot(
                    response.getIsBanned(),
                    response.getBanType().name(),
                    response.getReason(),
                    GrpcTimeMapper.toInstant(response.getExpiresAt()),
                    GrpcTimeMapper.toInstant(response.getBannedAt())
            );
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public ExternalUserProfile banUser(long userId, String reason, boolean permanent, Instant expiresAt, long operatorUserId) {
        try {
            BanUserRequest.Builder builder = BanUserRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setUserId(userId)
                    .setReason(normalize(reason))
                    .setOperatorUserId(operatorUserId)
                    .setBanType(permanent ? BanType.BAN_TYPE_PERMANENT : BanType.BAN_TYPE_TEMPORARY);
            if (!permanent && expiresAt != null) {
                builder.setExpiresAt(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(expiresAt.getEpochSecond())
                        .setNanos(expiresAt.getNano())
                        .build());
            }
            var response = userBanStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).banUser(builder.build());
            if (!response.getSuccess()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, normalizeError(response.getErrorMessage(), "封禁失败"));
            }
            return response.hasUser() ? toProfile(response.getUser()) : null;
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    public ExternalUserProfile unbanUser(long userId, String reason, long operatorUserId) {
        try {
            var response = userBanStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS).unbanUser(UnbanUserRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setUserId(userId)
                    .setReason(normalize(reason))
                    .setOperatorUserId(operatorUserId)
                    .build());
            if (!response.getSuccess()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, normalizeError(response.getErrorMessage(), "解封失败"));
            }
            return response.hasUser() ? toProfile(response.getUser()) : null;
        } catch (StatusRuntimeException ex) {
            throw toApiException(ex);
        }
    }

    private ExternalUserProfile toProfile(UserBasic user) {
        return new ExternalUserProfile(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getActive(),
                GrpcTimeMapper.toInstant(user.getBannedUntil()),
                GrpcTimeMapper.toInstant(user.getCreatedAt())
        );
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
            message = "用户服务调用失败";
        }
        return new ApiException(status, message);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeError(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
