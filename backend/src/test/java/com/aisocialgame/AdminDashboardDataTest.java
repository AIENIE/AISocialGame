package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.*;
import com.aisocialgame.service.safety.AiSafetyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDashboardDataTest {
    @Test void distinguishesConfirmedZeroModelsFromAnUnavailableModelService() {
        var proxy = mock(AiProxyService.class);
        var safety = mock(AiSafetyService.class);
        when(safety.summary()).thenReturn(new AiSafetyService.SafetySummary(0,0,0,0));
        var service = new AdminOpsService(mock(UserGrpcClient.class), mock(BillingGrpcClient.class),
                mock(BalanceService.class), mock(ProjectCreditService.class), proxy, mock(UserRepository.class),
                mock(RoomRepository.class), mock(CommunityPostRepository.class), mock(GameStateRepository.class),
                new AppProperties(), mock(AiDecisionTraceService.class), mock(AiPersonaMemoryRepository.class),
                mock(AiReflectionService.class), safety);
        when(proxy.listModelsForSystem()).thenReturn(List.of());
        assertEquals(0, service.dashboardSummary().getAiModels());
        when(proxy.listModelsForSystem()).thenThrow(new IllegalStateException("remote failure"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, service::dashboardSummary).getStatus());
    }
}
