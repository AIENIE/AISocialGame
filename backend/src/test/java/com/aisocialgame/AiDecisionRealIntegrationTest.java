package com.aisocialgame;

import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import com.aisocialgame.service.ai.AiDecisionResult;
import com.aisocialgame.service.ai.AiDecisionService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = AiSocialGameApplication.class, properties = {
        "grpc.client.ai.address=${AI_GRPC_ADDR:static://127.0.0.1:19003}",
        "app.external.aiservice-hmac-caller=${APP_EXTERNAL_AISERVICE_HMAC_CALLER:}",
        "app.external.aiservice-hmac-secret=${APP_EXTERNAL_AISERVICE_HMAC_SECRET:}",
        "app.ai.default-model=${APP_AI_DEFAULT_MODEL:}",
        "app.ai.system-user-id=${APP_AI_SYSTEM_USER_ID:1}"
})
@ActiveProfiles("test")
class AiDecisionRealIntegrationTest {
    @Autowired
    private AiDecisionService aiDecisionService;

    @Autowired
    private AiDecisionTraceRepository aiDecisionTraceRepository;

    @Test
    void realAiServiceCanProduceAndTraceDecision() {
        Assumptions.assumeTrue("1".equals(System.getenv("REAL_AI_INTEGRATION")), "REAL_AI_INTEGRATION=1 not set");
        Assumptions.assumeTrue(hasText(System.getenv("AI_GRPC_ADDR")), "AI_GRPC_ADDR not set");
        Assumptions.assumeTrue(hasText(System.getenv("APP_EXTERNAL_AISERVICE_HMAC_CALLER")), "AI HMAC caller not set");
        Assumptions.assumeTrue(hasText(System.getenv("APP_EXTERNAL_AISERVICE_HMAC_SECRET")), "AI HMAC secret not set");

        GameState state = new GameState("real-ai-room", "undercover", "DESCRIPTION");
        state.setPlayers(List.of(
                player("human-1", "一号", 1, false, "CIVILIAN", "咖啡"),
                player("ai-2", "二号", 2, true, "CIVILIAN", "咖啡"),
                player("human-3", "三号", 3, false, "UNDERCOVER", "奶茶")
        ));
        state.setLogs(new ArrayList<>(List.of(
                new GameLogEntry("speak", "一号：这东西平时早上经常会喝"),
                new GameLogEntry("speak", "三号：它有很多口味，也能加冰")
        )));
        state.setData(new HashMap<>());

        AiDecisionResult result = aiDecisionService.generateSpeech(state, state.getPlayers().get(1));

        assertTrue(hasText(result.content()));
        assertNotNull(result.traceId());
        assertTrue(aiDecisionTraceRepository.findById(result.traceId()).isPresent());
    }

    private GamePlayerState player(String id, String name, int seat, boolean ai, String role, String word) {
        GamePlayerState player = new GamePlayerState(id, name, seat, ai, ai ? "ai1" : null, "");
        player.setRole(role);
        player.setWord(word);
        player.setAlive(true);
        player.setConnectionStatus("ONLINE");
        return player;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
