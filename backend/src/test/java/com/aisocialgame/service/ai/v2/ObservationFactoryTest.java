package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.turtlesoup.*;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObservationFactoryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final AiPersonaMemoryRepository memoryRepository = mock(AiPersonaMemoryRepository.class);
    private final AiMemoryServiceV2 memories = new AiMemoryServiceV2(memoryRepository, personas);
    private final ObservationFactory factory = new ObservationFactory(personas, memories);
    private final UndercoverRuleSet undercover = new UndercoverRuleSet(new UndercoverWordCatalog());

    ObservationFactoryTest() {
        when(memoryRepository.findByPersonaIdAndGameIdAndRoleKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(personas.findById("persona")).thenReturn(new Persona("persona", "阿岚", "谨慎但友善", "", "自然短句", "按公开线索调整", 2, "尊重不同观点"));
    }

    @Test void changingHiddenAlignmentOrOpponentWordsCannotChangeAnActorsObservation() throws Exception {
        GameState state = wordState();
        VisibleObservation before = observe(state, "p0");
        String serialized = JSON.writeValueAsString(before);
        assertFalse(before.self().containsKey("role"));
        assertEquals("咖啡", before.self().get("word"));
        assertFalse(serialized.contains("隐藏对手词一"));
        player(state, "p0").setRole("UNDERCOVER");
        player(state, "p1").setRole("CIVILIAN");
        player(state, "p1").setWord("隐藏对手词二");
        state.getData().put("civilianWord", "隐藏对手词二");
        state.getData().put("undercoverWord", "咖啡");
        event(state, "ROLE_ASSIGNED", "p1", null, "", Map.of("word", "隐藏对手词二", "role", "CIVILIAN"), "GOD", List.of());
        assertEquals(serialized, JSON.writeValueAsString(observe(state, "p0")));
    }

    @Test void publicOwnPrivateAndGodEventsFollowDifferentVisibilityRules() {
        GameState state = wordState();
        event(state, "PUBLIC_TEST", "p1", null, "大家都能看到", Map.of("marker", "PUBLIC_VISIBLE"));
        event(state, "OWN_TEST", "p0", null, "", Map.of("marker", "OWN_VISIBLE"), "PRIVATE", List.of("p0"));
        event(state, "OTHER_TEST", "p1", null, "", Map.of("marker", "OTHER_SECRET"), "PRIVATE", List.of("p1"));
        event(state, "GOD_TEST", "p0", null, "", Map.of("marker", "GOD_SECRET"), "GOD", List.of("p0"));
        event(state, "UNKNOWN_TEST", "p0", null, "", Map.of("marker", "UNKNOWN_SECRET"), "NEW_VISIBILITY", List.of("p0"));
        String data = factory.visibleEvents(state, "p0").toString();
        assertTrue(data.contains("PUBLIC_VISIBLE")); assertTrue(data.contains("OWN_VISIBLE"));
        assertFalse(data.contains("OTHER_SECRET")); assertFalse(data.contains("GOD_SECRET")); assertFalse(data.contains("UNKNOWN_SECRET"));
        String spectator = factory.visibleEvents(state, "spectator").toString();
        assertTrue(spectator.contains("PUBLIC_VISIBLE")); assertFalse(spectator.contains("OWN_VISIBLE"));
    }

    @Test void importantOldEvidenceSurvivesWindowingButMemoryCannotOverrideAnEventAcl() {
        GameState state = wordState();
        event(state, "SPEAK", "p1", null, "较早的一条公开线索", Map.of());
        String important = lastEventId(state);
        event(state, "PRIVATE_TEST", "p1", null, "", Map.of("marker", "FORBIDDEN_OLD_EVIDENCE"), "PRIVATE", List.of("p1"));
        String secret = lastEventId(state);
        for (int i = 0; i < 60; i++) event(state, "SPEAK", "p1", null, "后续公开线索" + i, Map.of());
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of("beliefs", Map.of("p1", Map.of("evidenceEventIds", List.of(important, secret))))));
        VisibleObservation observation = observe(state, "p0");
        assertEquals(49, observation.events().size());
        assertTrue(observation.events().stream().anyMatch(e -> important.equals(e.get("eventId"))));
        assertTrue(observation.events().stream().noneMatch(e -> secret.equals(e.get("eventId"))));
        assertFalse(observation.events().toString().contains("FORBIDDEN_OLD_EVIDENCE"));
    }

    @Test void ownStatementsSurviveLongRoundsWithoutPullingInHiddenEvents() {
        GameState state = wordState();
        event(state, "SPEAK", "p0", null, "我当时只描述了香气。", Map.of());
        String own = lastEventId(state);
        event(state, "SPEAK", "p1", null, "别人的私密发言", Map.of(), "PRIVATE", List.of("p1"));
        for (int i = 0; i < 60; i++) event(state, "SPEAK", "p1", null, "公开线索" + i, Map.of());
        var observation = observe(state, "p0");
        assertTrue(observation.events().stream().anyMatch(e -> own.equals(e.get("eventId"))));
        assertFalse(AiGrounding.context(observation).toString().contains("别人的私密发言"));
    }

    @Test void actualRuleSpeechEventsSurviveLongHistoryAndReachContinuityInOriginalOrder() {
        for (String scenario : List.of("undercover:SPEAK", "werewolf:SPEAK", "turtle_soup:DISCUSS", "turtle_soup:ASK_QUESTION", "turtle_soup:SUBMIT_SOLUTION")) {
            String[] parts = scenario.split(":");
            GameRuleSet rules = switch (parts[0]) {
                case "undercover" -> undercover;
                case "werewolf" -> new WerewolfRuleSet();
                default -> new TurtleSoupRuleSet(new TurtleSoupCaseCatalog(JSON));
            };
            Room room = room(parts[0], "werewolf".equals(parts[0]) ? 6 : 4);
            room.getConfig().putAll(Map.of("template", "standard", "speakTime", 60));
            if ("turtle_soup".equals(parts[0])) room.getConfig().put("caseId", new TurtleSoupCaseCatalog(JSON).cases().getFirst().id());
            room.getSeats().forEach(s -> s.setAi(false));
            GameState state = rules.initialize(room, LocalDateTime.now());
            if ("werewolf".equals(parts[0])) { state.setPhase("DAY_DISCUSS"); state.setCurrentSeat(0); }
            String actor = "undercover".equals(parts[0])
                    ? state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(), state.getCurrentSeat())).findFirst().orElseThrow().getPlayerId() : "p0";
            rules.apply(state, actor, action(parts[1], "我先保留这个判断，等下一条线索再核对。", null), LocalDateTime.now());
            Map<String, Object> actual = maps(state.getData().get("events")).stream()
                    .filter(AiSpeechEvents::isSpeech).filter(e -> actor.equals(e.get("actorId"))).findFirst().orElseThrow();
            String ownId = text(actual.get("eventId"));
            String hiddenRecipient = "p0".equals(actor) ? "p1" : "p0";
            event(state, text(actual.get("type")), actor, null, "不可见的同类事件", Map.of(), "PRIVATE", List.of(hiddenRecipient));
            String hiddenId = lastEventId(state);
            for (int i = 0; i < 60; i++) event(state, "PUBLIC_NOTICE", null, null, "后续事件" + i, Map.of());
            VisibleObservation observation = factory.build(state, rules, turn(state, actor, parts[1]));
            var selected = observation.events().stream().map(e -> e.get("eventId")).toList();
            assertTrue(selected.contains(ownId), scenario);
            assertFalse(selected.contains(hiddenId), scenario);
            assertEquals(selected.size(), new HashSet<>(selected).size());
            var originalOrder = factory.visibleEvents(state, actor).stream().map(e -> e.get("eventId")).filter(selected::contains).toList();
            assertEquals(originalOrder, selected);
            var statements = maps(AiGrounding.context(observation).get("ownStatements"));
            assertEquals(1, statements.size(), scenario);
            assertEquals(ownId, statements.getFirst().get("eventId"));
            assertEquals(actual.get("type"), statements.getFirst().get("type"));
            assertFalse(statements.toString().contains("不可见"));
        }
    }

    @Test void sharedSpeechRetentionKeepsOnlyLastEightOwnEventsBeyondTheWindow() {
        GameState state = wordState();
        List<String> own = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            event(state, "SPEECH", "p0", null, "本人历史" + i, Map.of()); own.add(lastEventId(state));
        }
        for (int i = 0; i < 60; i++) event(state, "PUBLIC_NOTICE", null, null, "后续事件" + i, Map.of());
        var observation = observe(state, "p0");
        assertEquals(56, observation.events().size());
        assertEquals(own.subList(2, 10), maps(AiGrounding.context(observation).get("ownStatements")).stream().map(e -> e.get("eventId")).toList());
    }

    @Test void hypothesisAndCommitmentEvidenceSurvivesWithoutPromotingHiddenEventsOrLegacyText() {
        GameState state = wordState();
        event(state, "PUBLIC_TEST", "p1", null, "早期公共依据", Map.of()); String publicId = lastEventId(state);
        event(state, "PRIVATE_TEST", "p0", null, "本人的可见依据", Map.of(), "PRIVATE", List.of("p0")); String privateId = lastEventId(state);
        event(state, "PRIVATE_TEST", "p1", null, "他人的不可见依据", Map.of(), "PRIVATE", List.of("p1")); String secretId = lastEventId(state);
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of(
                "hypotheses", List.of(Map.of("text", "尚未确认", "evidenceEventIds", List.of(publicId, secretId))),
                "commitments", List.of("旧版承诺", Map.of("text", "待核对承诺", "eventId", privateId)))));
        for (int i = 0; i < 60; i++) event(state, "PUBLIC_NOTICE", null, null, "事件" + i, Map.of());
        var observation = observe(state, "p0");
        assertEquals(50, observation.events().size());
        assertTrue(observation.events().stream().anyMatch(e -> publicId.equals(e.get("eventId"))));
        assertTrue(observation.events().stream().anyMatch(e -> privateId.equals(e.get("eventId"))));
        assertFalse(observation.events().stream().anyMatch(e -> secretId.equals(e.get("eventId"))));
        var context = AiGrounding.context(observation);
        assertEquals(2, maps(context.get("commitmentsToVerify")).size());
        assertTrue(maps(context.get("ownStatements")).isEmpty());
        assertFalse(observation.events().toString().contains("他人的不可见依据"));
    }

    @Test void asynchronousObservationIsDetachedFromLaterEntityAndNestedMapMutation() throws Exception {
        GameState state = wordState();
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("nested", new LinkedHashMap<>(Map.of("value", "original"))));
        event(state, "SPEAK", "p1", null, "公开发言", payload);
        VisibleObservation observation = observe(state, "p0");
        String before = JSON.writeValueAsString(observation);
        player(state, "p0").setWord("later-word");
        List<Map<String, Object>> events = maps(state.getData().get("events"));
        Map<String, Object> altered = new LinkedHashMap<>(events.getLast());
        altered.put("message", "later-event"); altered.put("data", Map.of("nested", Map.of("value", "later-data")));
        events.set(events.size() - 1, altered); state.getData().put("events", events);
        state.getData().put("wordKnowledgeByPlayer", Map.of("p0", Map.of("definition", "later-knowledge")));
        assertEquals(before, JSON.writeValueAsString(observation));
    }

    @Test void customWordsAndPoolMetadataAreExcludedEvenIfConfigContainsPrivateLookingKeys() throws Exception {
        Room room = room("undercover", 4);
        room.setHostUserId("author");
        room.getConfig().putAll(Map.of("wordPack", "custom", "hostMode", "AUTHOR", "speakTime", 60,
                "customWords", List.of(Map.of("wordA", "PUBLIC_CONFIG_SECRET_A", "wordB", "PUBLIC_CONFIG_SECRET_B")), "usedWordPairIds", List.of("private-pair-id")));
        room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", "红色信封", "wordB", "蓝色卡片")));
        GameState state = undercover.initialize(room, LocalDateTime.now());
        VisibleObservation observation = observe(state, "p0");
        String serialized = JSON.writeValueAsString(observation);
        String own = player(state, "p0").getWord();
        assertTrue(serialized.contains(own));
        assertFalse(serialized.contains("红色信封".equals(own) ? "蓝色卡片" : "红色信封"));
        assertFalse(serialized.contains("PUBLIC_CONFIG_SECRET"));
        assertFalse(serialized.contains("private-pair-id"));
        assertFalse(observation.rules().containsKey("customWords"));
        assertFalse(observation.rules().containsKey("usedWordPairIds"));
        assertEquals("custom", observation.rules().get("wordPack"));
    }

    @Test void blankReceivesNeitherWordsNorWordKnowledge() throws Exception {
        GameState state = wordState();
        player(state, "p0").setRole("BLANK"); player(state, "p0").setWord("");
        VisibleObservation observation = observe(state, "p0");
        assertEquals("BLANK", observation.self().get("role"));
        assertEquals("", observation.self().get("word"));
        assertFalse(observation.privateFacts().containsKey("wordKnowledge"));
        String serialized = JSON.writeValueAsString(observation);
        assertFalse(serialized.contains("咖啡")); assertFalse(serialized.contains("隐藏对手词一"));
    }

    @Test void changingSoupTruthChangesOnlyTheHostObservation() throws Exception {
        TurtleSoupCaseCatalog catalog = new TurtleSoupCaseCatalog(JSON);
        TurtleSoupRuleSet rules = new TurtleSoupRuleSet(catalog);
        Room room = room("turtle_soup", 2); room.getConfig().put("caseId", catalog.cases().getFirst().id());
        room.getSeats().getFirst().setAi(false);
        GameState state = rules.initialize(room, LocalDateTime.now());
        TurnRequest playerTurn = turn(state, "p0", "DISCUSS");
        String before = JSON.writeValueAsString(factory.build(state, rules, playerTurn));
        state.getData().put("hostTruth", Map.of("solution", "ONLY_THE_HOST_CAN_SEE_THIS_CHANGED_TRUTH", "facts", List.of()));
        assertEquals(before, JSON.writeValueAsString(factory.build(state, rules, playerTurn)));
        VisibleObservation host = factory.build(state, rules, turn(state, HOST, "HOST_VERDICT"));
        assertTrue(JSON.writeValueAsString(host).contains("ONLY_THE_HOST_CAN_SEE_THIS_CHANGED_TRUTH"));
        assertTrue(host.memory().isEmpty());
        assertEquals("HOST", host.self().get("role"));
    }

    @Test void longSoupDiscussionRetainsTheSurfaceAndConfirmedAnswersWithoutHostTruth() throws Exception {
        TurtleSoupCaseCatalog catalog = new TurtleSoupCaseCatalog(JSON);
        TurtleSoupRuleSet rules = new TurtleSoupRuleSet(catalog);
        Room room = room("turtle_soup", 2); room.getConfig().put("caseId", catalog.cases().getFirst().id());
        room.getSeats().getFirst().setAi(false);
        GameState state = rules.initialize(room, LocalDateTime.now());
        event(state, "TURTLE_SOUP_QUESTION", HOST, "p0", "主持：否。", Map.of("question", "已经确认过的早期问题", "verdict", "NO", "duplicate", false));
        String confirmation = lastEventId(state);
        event(state, "TURTLE_SOUP_START", HOST, null, "HIDDEN_ANSWER_MUST_STAY_PRIVATE", Map.of(), "PRIVATE", List.of(HOST));
        for (int i = 0; i < 60; i++) event(state, "TURTLE_SOUP_DISCUSSION", "p0", null, "后续公开讨论" + i, Map.of());
        VisibleObservation observation = factory.build(state, rules, turn(state, "p1", "DISCUSS"));
        assertEquals(50, observation.events().size());
        assertTrue(observation.events().stream().anyMatch(e -> "TURTLE_SOUP_START".equals(e.get("type"))));
        assertTrue(observation.events().stream().anyMatch(e -> confirmation.equals(e.get("eventId"))));
        String serialized = JSON.writeValueAsString(observation);
        assertTrue(serialized.contains(String.valueOf(state.getData().get("surface"))));
        assertFalse(serialized.contains("HIDDEN_ANSWER_MUST_STAY_PRIVATE"));
        assertFalse(serialized.contains("hostTruth"));
        assertTrue(observation.privateFacts().isEmpty());
    }

    private VisibleObservation observe(GameState state, String actor) { return factory.build(state, undercover, turn(state, actor, "SPEAK")); }
    private String lastEventId(GameState state) { return text(maps(state.getData().get("events")).getLast().get("eventId")); }
    private GameState wordState() {
        GameState state = newState(room("undercover", 4), LocalDateTime.now());
        state.setPhase("DESCRIPTION"); state.setCurrentSeat(0);
        state.getPlayers().forEach(p -> { p.setRole("CIVILIAN"); p.setWord("咖啡"); });
        player(state, "p1").setRole("UNDERCOVER"); player(state, "p1").setWord("隐藏对手词一");
        state.getData().put("civilianWord", "咖啡"); state.getData().put("undercoverWord", "隐藏对手词一");
        state.getData().put("wordKnowledgeByPlayer", Map.of("p0", Map.of("word", "咖啡", "definition", "用烘焙种子冲泡的饮品", "attributes", List.of("香气浓郁", "经常用于提神", "可能偏苦"), "scenes", List.of("上班路上喝一杯"))));
        return state;
    }
    private Room room(String game, int count) {
        Room room = new Room("observation-room", game, "观察权限测试", RoomStatus.WAITING, count, false, null, "text", new LinkedHashMap<>());
        room.setHostUserId("p0");
        for (int i = 0; i < count; i++) room.getSeats().add(new RoomSeat(i, "p" + i, "玩家" + i, true, "persona", "", true, i == 0));
        return room;
    }
}
