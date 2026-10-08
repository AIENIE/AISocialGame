package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.*;
import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.websocket.GamePushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

class PersonaPresetTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final PersonaRepository personas = new PersonaRepository();

    @Test void catalogSerializesCompleteDistinctPresetsWithStableIdsAndExplicitTraits() throws Exception {
        assertEquals(List.of("ai1", "ai2", "ai3", "ai4"), personas.findAll().stream().map(Persona::getId).toList());
        Set<Map<String, String>> guides = new HashSet<>();
        for (var p : personas.findAll()) {
            var node = json.readTree(json.writeValueAsString(p));
            assertEquals(1, node.path("presetVersion").asInt());
            assertEquals(Set.of("questioning", "stance", "revision", "commitment"), p.getBehaviorGuide().keySet());
            assertTrue(p.getBehaviorGuide().values().stream().allMatch(v -> !v.isBlank()));
            assertFalse(p.getSpeechStyle().isBlank()); assertFalse(p.getStrategyStyle().isBlank());
            guides.add(p.getBehaviorGuide());
        }
        assertEquals(4, guides.size());
        assertTrue(personas.findById("ai2").getRiskPreference() > personas.findById("ai1").getRiskPreference());
        assertTrue(personas.findById("ai3").getSociability() > personas.findById("ai4").getSociability());
        assertEquals(2, new Persona("ai1", "旧名", "旧风格", "").getRiskPreference(), "constructors must not infer personality from ID");
    }

    @Test void legacyCompletionIsPureAndUnknownIdKeepsTakeoverDefault() {
        Persona old = new Persona("ai1", "旧名", "旧风格", "", "旧口吻", "旧策略", 1, "旧记忆");
        var completed = PersonaPresets.complete(old);
        assertEquals("旧口吻", completed.getSpeechStyle()); assertEquals("旧策略", completed.getStrategyStyle());
        assertEquals(personas.findById("ai1").getBehaviorGuide(), completed.getBehaviorGuide());
        assertEquals(0, old.getPresetVersion()); assertTrue(old.getBehaviorGuide().isEmpty());
        Persona partial = new Persona("ai2", "旧名", "旧风格", "", "口吻", "策略", 2, "", 3, 2, 3,
                Map.of("questioning", "已保存的提问倾向"), 1);
        var restored = PersonaPresets.complete(partial);
        assertEquals("已保存的提问倾向", restored.getBehaviorGuide().get("questioning"));
        assertEquals(personas.findById("ai2").getBehaviorGuide().get("stance"), restored.getBehaviorGuide().get("stance"));
        assertEquals(1, partial.getBehaviorGuide().size());
        Persona unknown = new Persona("unknown", "旧名", "", "");
        assertSame(unknown, PersonaPresets.complete(unknown));
        var state = newState(room("undercover"), LocalDateTime.now());
        state.getPlayers().getFirst().setPersonaId("unknown");
        var observation = factory(personas).build(state, new UndercoverRuleSet(new UndercoverWordCatalog()), turn(state, "host", "SPEAK"));
        assertEquals("代管玩家", observation.persona().get("name"));
        assertEquals(0, observation.persona().get("presetVersion"));
    }

    @Test void selectedIdSurvivesRoomSeatingAllThreeGamesAndDetachedPrivateObservation() throws Exception {
        for (String game : List.of("undercover", "werewolf", "turtle_soup")) {
            for (Persona preset : personas.findAll()) {
                Room room = room(game);
                var repository = mock(RoomRepository.class);
                when(repository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
                var names = mock(AiNameService.class); when(names.localName(preset)).thenReturn("生成昵称");
                var service = new RoomService(repository, mock(GameService.class), personas, names, mock(GamePushService.class), mock(com.aisocialgame.service.WriteRateLimiter.class), mock(org.springframework.transaction.PlatformTransactionManager.class));
                org.springframework.test.util.ReflectionTestUtils.setField(service, "lifecycle", new com.aisocialgame.service.RoomLifecycle(repository, mock(GamePushService.class), mock(org.springframework.transaction.PlatformTransactionManager.class)));
                User host = new User(); host.setId("host");
                service.addAi(room.getId(), preset.getId(), host);
                var seat = room.getSeats().getLast();
                assertEquals("生成昵称", seat.getDisplayName()); assertEquals(preset.getId(), seat.getPersonaId());
                verify(names).localName(preset);
                for (int i = 2; i < 6; i++) room.getSeats().add(new RoomSeat(i, "p" + i, "人" + i, false, null, "", true, false));
                var catalog = new TurtleSoupCaseCatalog(json);
                GameRuleSet rules = switch (game) {
                    case "undercover" -> new UndercoverRuleSet(new UndercoverWordCatalog());
                    case "werewolf" -> new WerewolfRuleSet();
                    default -> new TurtleSoupRuleSet(catalog);
                };
                if (game.equals("turtle_soup")) room.getConfig().put("caseId", catalog.cases().getFirst().id());
                GameState state = rules.initialize(room, LocalDateTime.now());
                assertEquals(preset.getId(), player(state, seat.getPlayerId()).getPersonaId());
                event(state, "PRIVATE_TEST", "p2", null, "SECRET_OPPONENT_MARKER", Map.of(), "PRIVATE", List.of("p2"));
                var observation = factory(personas).build(state, rules, turn(state, seat.getPlayerId(), "SPEAK"));
                assertEquals(preset.getId(), observation.persona().get("id"));
                assertEquals(preset.getBehaviorGuide(), observation.persona().get("behaviorGuide"));
                assertEquals(1, observation.persona().get("presetVersion"));
                assertFalse(json.writeValueAsString(observation).contains("SECRET_OPPONENT_MARKER"));
                assertEquals(AiTurnGenerator.PROMPT_VERSION, state.getData().get("promptVersion"));
            }
        }
    }

    private ObservationFactory factory(PersonaRepository catalog) {
        return new ObservationFactory(catalog, new AiMemoryServiceV2(mock(AiPersonaMemoryRepository.class), catalog));
    }
    private Room room(String game) {
        Room room = new Room("presets-" + game, game, "离线", RoomStatus.WAITING, 6, false, null, "text", new LinkedHashMap<>(Map.of("template", "standard")));
        room.setHostUserId("host"); room.getSeats().add(new RoomSeat(0, "host", "真人", false, null, "", true, true));
        return room;
    }
}
