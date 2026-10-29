package com.aisocialgame;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.client.UserGrpcClient;
import com.aisocialgame.integration.grpc.dto.ExternalUserProfile;
import com.aisocialgame.logging.SafeLogValue;
import com.aisocialgame.model.credit.CreditRedeemCode;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.AdminOpsService;
import com.aisocialgame.service.AiProxyService;
import com.aisocialgame.service.BalanceService;
import com.aisocialgame.service.ProjectCreditService;
import com.aisocialgame.service.ai.AiDecisionTraceService;
import com.aisocialgame.service.safety.AiSafetyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOpsServiceLoggingTest {
    private static final String BEARER_CODE = "ADMIN-REDEEM-SECRET";

    @Test
    void redeemCodeSuccessUsesDatabaseIdWithoutBearerCode() {
        ProjectCreditService projectCreditService = mock(ProjectCreditService.class);
        CreditRedeemCode created = mock(CreditRedeemCode.class);
        when(created.getId()).thenReturn(42L);
        when(projectCreditService.createRedeemCode(
                BEARER_CODE, 10L, "CREDIT_TYPE_TEMP", null, null, null, true))
                .thenReturn(created);
        AdminOpsService service = service(projectCreditService);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            service.createRedeemCode(
                    BEARER_CODE, 10L, "CREDIT_TYPE_TEMP", null, null, null, true, "admin");

            String logged = auditEvent(appender).getFormattedMessage();
            Assertions.assertTrue(logged.contains("actorFingerprint=" + SafeLogValue.fingerprint("admin")));
            Assertions.assertFalse(logged.contains("actor=admin"));
            Assertions.assertTrue(logged.contains("operatorUserId=1"));
            Assertions.assertTrue(logged.contains("action=admin.redeem-code.create"));
            Assertions.assertTrue(logged.contains("targetId=42"));
            Assertions.assertTrue(logged.contains("result=SUCCESS"));
            Assertions.assertFalse(logged.contains(BEARER_CODE));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void banAndUnbanUseConfiguredCrossServiceOperatorUserId() {
        ApiException failure = new ApiException(HttpStatus.BAD_GATEWAY, "remote detail must stay out of logs");
        RecordingUserGrpcClient userGrpcClient = new RecordingUserGrpcClient(failure);
        AppProperties properties = new AppProperties();
        properties.getAdmin().setOperatorUserId(23L);
        AdminOpsService service = service(userGrpcClient, mock(ProjectCreditService.class), properties);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            Assertions.assertSame(failure, Assertions.assertThrows(ApiException.class, () ->
                    service.banUser(1001L, "private ban reason", true, null, "admin")));
            Assertions.assertSame(failure, Assertions.assertThrows(ApiException.class, () ->
                    service.unbanUser(1001L, "private unban reason", "admin")));

            Assertions.assertEquals(23L, userGrpcClient.lastBanOperatorUserId);
            Assertions.assertEquals(23L, userGrpcClient.lastUnbanOperatorUserId);
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Admin operation"))
                    .reduce("", (left, right) -> left + "\n" + right);
            Assertions.assertTrue(logged.contains("operatorUserId=23"));
            Assertions.assertTrue(logged.contains("action=admin.user.ban"));
            Assertions.assertTrue(logged.contains("action=admin.user.unban"));
            Assertions.assertFalse(logged.contains("private ban reason"));
            Assertions.assertFalse(logged.contains("private unban reason"));
            Assertions.assertFalse(logged.contains("remote detail"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void operatorUserIdDefaultsToOneAndRejectsNonPositiveValues() {
        Assertions.assertEquals(1L, new AppProperties().getAdmin().getOperatorUserId());

        AppProperties invalid = new AppProperties();
        invalid.getAdmin().setOperatorUserId(0L);
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                service(new RecordingUserGrpcClient(null), mock(ProjectCreditService.class), invalid));
    }

    @Test
    void redeemCodeFailureLogsOnlyTypeAndRethrows() {
        ProjectCreditService projectCreditService = mock(ProjectCreditService.class);
        ApiException failure = new ApiException(HttpStatus.BAD_REQUEST, "failure mentions " + BEARER_CODE);
        when(projectCreditService.createRedeemCode(
                BEARER_CODE, 10L, "CREDIT_TYPE_TEMP", null, null, null, true))
                .thenThrow(failure);
        AdminOpsService service = service(projectCreditService);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            Assertions.assertSame(failure, Assertions.assertThrows(ApiException.class, () ->
                    service.createRedeemCode(
                            BEARER_CODE, 10L, "CREDIT_TYPE_TEMP", null, null, null, true, "admin")));

            ILoggingEvent event = auditEvent(appender);
            String logged = event.getFormattedMessage();
            Assertions.assertTrue(logged.contains("targetId=unassigned"));
            Assertions.assertTrue(logged.contains("result=FAILURE"));
            Assertions.assertTrue(logged.contains("errorType=ApiException"));
            Assertions.assertFalse(logged.contains(BEARER_CODE));
            Assertions.assertNull(event.getThrowableProxy());
        } finally {
            detachAppender(appender);
        }
    }

    private AdminOpsService service(ProjectCreditService projectCreditService) {
        return service(new RecordingUserGrpcClient(null), projectCreditService, new AppProperties());
    }

    private AdminOpsService service(UserGrpcClient userGrpcClient,
                                    ProjectCreditService projectCreditService,
                                    AppProperties properties) {
        return new AdminOpsService(
                userGrpcClient,
                mock(BillingGrpcClient.class),
                mock(BalanceService.class),
                projectCreditService,
                mock(AiProxyService.class),
                mock(UserRepository.class),
                mock(RoomRepository.class),
                mock(CommunityPostRepository.class),
                mock(GameStateRepository.class),
                properties,
                mock(AiDecisionTraceService.class),
                mock(AiPersonaMemoryRepository.class),
                mock(AiSafetyService.class)
        );
    }

    private ILoggingEvent auditEvent(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Admin operation"))
                .findFirst()
                .orElseThrow();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
        appender.stop();
    }

    private Logger logger() {
        return (Logger) LoggerFactory.getLogger(AdminOpsService.class);
    }

    private static final class RecordingUserGrpcClient extends UserGrpcClient {
        private final RuntimeException failure;
        private long lastBanOperatorUserId;
        private long lastUnbanOperatorUserId;

        private RecordingUserGrpcClient(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ExternalUserProfile banUser(long userId,
                                           String reason,
                                           boolean permanent,
                                           java.time.Instant expiresAt,
                                           long operatorUserId) {
            lastBanOperatorUserId = operatorUserId;
            if (failure != null) {
                throw failure;
            }
            return null;
        }

        @Override
        public ExternalUserProfile unbanUser(long userId, String reason, long operatorUserId) {
            lastUnbanOperatorUserId = operatorUserId;
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }
}
