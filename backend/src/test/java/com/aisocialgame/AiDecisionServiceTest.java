package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.model.AiDecisionTrace;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.PersonaRepository;
import com.aisocialgame.service.ai.AiBeliefService;
import com.aisocialgame.service.ai.AiDecisionResult;
import com.aisocialgame.service.ai.AiDecisionService;
import com.aisocialgame.service.ai.AiDecisionTraceService;
import com.aisocialgame.service.ai.AiQualityService;
import com.aisocialgame.service.ai.AiReflectionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiDecisionServiceTest {

    @Test
    void decideVoteUsesStructuredAiTargetSeat() {
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new AiChatResult("{\"targetSeat\":3,\"reason\":\"3号描述最模糊\"}", "test", 1, 1));
        AiDecisionService service = service(aiGrpcClient);

        GameState state = undercoverState();
        GamePlayerState actor = state.getPlayers().get(1);

        AiDecisionResult result = service.decideVote(state, actor);

        Assertions.assertEquals("p3", result.targetPlayerId());
        Assertions.assertFalse(result.fallback());
        Assertions.assertEquals("3号描述最模糊", result.reason());
        Assertions.assertNotNull(result.traceId());
        Assertions.assertTrue(result.logMetadata().containsKey("aiTraceId"));
    }

    @Test
    void illegalAiVoteFallsBackToDeterministicLegalTarget() {
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new AiChatResult("{\"targetSeat\":99,\"reason\":\"非法座位\"}", "test", 1, 1));
        AiDecisionService service = service(aiGrpcClient);

        GameState state = undercoverState();
        GamePlayerState actor = state.getPlayers().get(1);

        AiDecisionResult result = service.decideVote(state, actor);

        Assertions.assertEquals("p3", result.targetPlayerId());
        Assertions.assertTrue(result.fallback());
        Assertions.assertTrue(result.qualityFlags().contains("FALLBACK_USED"));
    }

    @Test
    void werewolfNightActionUsesStructuredAiDecision() {
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new AiChatResult("{\"action\":\"WOLF_KILL\",\"targetSeat\":3,\"reason\":\"先刀发言强势的人\"}", "test", 1, 1));
        AiDecisionService service = service(aiGrpcClient);

        GameState state = werewolfState();
        GamePlayerState wolf = state.getPlayers().get(0);

        AiDecisionResult result = service.decideNightAction(state, wolf);

        Assertions.assertEquals("WOLF_KILL", result.action());
        Assertions.assertEquals("p3", result.targetPlayerId());
        Assertions.assertFalse(result.fallback());
    }

    @Test
    void decisionPromptPersistsBeliefAndMemoryInState() {
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.chatCompletions(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new AiChatResult("{\"content\":\"它很常见，但我先不说太具体\",\"reason\":\"避免暴露词语\",\"confidence\":0.8,\"evidence\":[\"一号发言偏泛\"],\"reflection\":\"继续观察三号\"}", "test-model", 9, 5));
        AiDecisionService service = service(aiGrpcClient);

        GameState state = undercoverState();
        GamePlayerState actor = state.getPlayers().get(1);

        AiDecisionResult result = service.generateSpeech(state, actor);

        Assertions.assertEquals("它很常见，但我先不说太具体", result.content());
        Assertions.assertEquals(0.8, result.confidence());
        Assertions.assertTrue(state.getData().containsKey("aiBeliefs"));
        Assertions.assertTrue(state.getData().containsKey("aiShortMemories"));
    }

    private AiDecisionService service(AiGrpcClient aiGrpcClient) {
        AppProperties appProperties = new AppProperties();
        appProperties.setProjectKey("aisocialgame-test");
        appProperties.getAi().setSystemUserId(1);
        appProperties.getAi().setDefaultModel("test-model");
        AiDecisionTraceRepository traceRepository = mock(AiDecisionTraceRepository.class);
        when(traceRepository.save(any(AiDecisionTrace.class))).thenAnswer(invocation -> {
            AiDecisionTrace trace = invocation.getArgument(0);
            trace.setId(100L);
            return trace;
        });
        AiPersonaMemoryRepository memoryRepository = mock(AiPersonaMemoryRepository.class);
        when(memoryRepository.findByPersonaIdAndGameIdAndRoleKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AiReflectionService reflectionService = new AiReflectionService(memoryRepository);
        return new AiDecisionService(
                aiGrpcClient,
                appProperties,
                new PromptProperties(),
                new PersonaRepository(),
                new AiBeliefService(),
                new AiQualityService(),
                reflectionService,
                new AiDecisionTraceService(traceRepository)
        );
    }

    private GameState undercoverState() {
        GameState state = new GameState("room-1", "undercover", "VOTING");
        List<GamePlayerState> players = List.of(
                player("p1", "一号", 1, false, "CIVILIAN", "可口可乐"),
                player("ai2", "二号", 2, true, "UNDERCOVER", "百事可乐"),
                player("p3", "三号", 3, false, "CIVILIAN", "可口可乐")
        );
        state.setPlayers(players);
        state.setLogs(new ArrayList<>(List.of(
                new GameLogEntry("speak", "一号：它是常见饮料"),
                new GameLogEntry("speak", "三号：我先听听")
        )));
        state.setData(new HashMap<>());
        return state;
    }

    private GameState werewolfState() {
        GameState state = new GameState("room-2", "werewolf", "NIGHT");
        state.setPlayers(List.of(
                player("wolf", "一号", 1, true, "WEREWOLF", null),
                player("p2", "二号", 2, false, "WEREWOLF", null),
                player("p3", "三号", 3, false, "SEER", null)
        ));
        state.setData(new HashMap<>());
        return state;
    }

    private GamePlayerState player(String id, String name, int seat, boolean ai, String role, String word) {
        GamePlayerState player = new GamePlayerState(id, name, seat, ai, ai ? "ai1" : null, "");
        player.setRole(role);
        player.setWord(word);
        player.setAlive(true);
        player.setConnectionStatus("ONLINE");
        return player;
    }
}
