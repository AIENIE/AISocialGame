package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.controller.AiController;
import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AiProxyService;
import com.aisocialgame.service.AiStreamConcurrencyLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiControllerBudgetTest {
    @Test
    void streamingRejectsBeforeCommittingSseResponseOrAcquiringPermit() {
        TaskExecutor executor = mock(TaskExecutor.class);
        AiStreamConcurrencyLimiter limiter = new AiStreamConcurrencyLimiter(1);
        AiController controller = new AiController(new AiProxyService(mock(AiGrpcClient.class), new AppProperties()), executor, limiter);
        User user = new User(); user.setId("user"); user.setExternalUserId(42L);
        ApiException failure = assertThrows(ApiException.class, () -> controller.chatStream(new AiChatRequest(), user));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals("BUDGET_UNAVAILABLE", failure.getCode());
        verifyNoInteractions(executor);
        var permit = limiter.tryAcquire("user");
        assertNotNull(permit);
        permit.close();
    }
}
