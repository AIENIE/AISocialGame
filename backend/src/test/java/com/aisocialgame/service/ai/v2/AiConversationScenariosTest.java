package com.aisocialgame.service.ai.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.service.safety.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConversationScenariosTest {
    @Test void twelveRuleProducedScenariosAcrossFourPresetsReachMockRpcAndSubmitLegally() throws Exception {
        var scenarios = new AiConversationScenarios().load();
        assertEquals(48, scenarios.size());
        assertEquals(12, scenarios.stream().map(AiConversationScenarios.Scenario::id).distinct().count());
        var json = MilestoneClosureScenarios.JSON;
        List<Map<String,Object>> samples = new ArrayList<>();
        Map<String, String> sharedContext = new HashMap<>();
        Set<String> guides = new HashSet<>();
        for (var s : scenarios) {
            var o = s.observation(); var session = s.session();
            assertEquals(s.expectedPhase(), o.phase());
            assertTrue(o.legalActions().stream().anyMatch(a -> s.expectedAction().equals(a.type())));
            if (!s.requiredEventId().isEmpty()) assertTrue(o.events().stream().anyMatch(e -> s.requiredEventId().equals(e.get("eventId"))));
            var conversation = session.rule.conversation(o);
            assertEquals(o.legalActions().stream().map(a -> a.type()).toList(), conversation.actions().stream().map(a -> a.type()).toList());
            if ("ANSWER_PLAYER".equals(s.expectedAction())) {
                assertEquals(1, conversation.replyTo().size());
                assertEquals(s.requiredEventId(), conversation.replyTo().getFirst().eventId());
            } else assertTrue(conversation.replyTo().isEmpty());
            String context = json.writeValueAsString(conversation);
            assertEquals(sharedContext.computeIfAbsent(s.id(), k -> context), context, "same situation, no persona override");
            guides.add(json.writeValueAsString(o.persona().get("behaviorGuide")));

            var client = mock(AiGrpcClient.class); var safety = mock(AiSafetyService.class);
            when(safety.review(anyString(), any(AiSafetyContext.class))).thenAnswer(c -> new AiSafetyResult("ALLOW", "LOW", "NONE", "", c.getArgument(0), null));
            var properties = new AppProperties(); properties.setProjectKey("offline"); properties.getAi().setSystemUserId(1); properties.getAi().setDefaultModel("mock-only");
            String type = s.expectedAction();
            if ((s.id().endsWith("no_new_contribution") || s.id().endsWith("human_prepares_question")) && Set.of("ai1", "ai2").contains(s.personaId())) type = "PASS";
            if (s.id().endsWith("no_new_information") && "ai1".equals(s.personaId())) type = "SKIP";
            var capability = o.legalActions().stream().filter(a -> a.type().equals(s.expectedAction())).findFirst().orElseThrow();
            String content = "ai1".equals(s.personaId()) ? "明白。" : "ai3".equals(s.personaId()) ? "我仍保留另一种解释。" : "这个更正有道理，我调整判断。";
            if ("ai2".equals(s.personaId())) content = "我把判断和确认分开。相似的表述不等于相同的依据。需要核对具体条件。暂时保留另一个解释。";
            if ("ai2".equals(s.personaId()) && "werewolf".equals(o.gameId()) && "SPEAK".equals(type))
                content = "我把判断和确认分开。相似的发言可以成为询问的起点，但不足以确认阵营。需要核对每个人在什么信息出现后作出表态。现在我保留另一种解释：措辞相近也可能只是注意到了同一处细节。接下来我更关心谁愿意给出具体依据，以及面对反证时怎样回应。这些仍是判断方向，不是身份结论。";
            if (s.id().endsWith("first_description")) content = "我会把它和熟悉的日常场景联系起来。";
            if ("ASK_QUESTION".equals(type)) content = "事情发生在夜间吗？";
            if (Set.of("PASS", "SKIP").contains(type)) content = null;
            var a = action(type, content, capability.targets().isEmpty() ? null : capability.targets().getFirst());
            var output = new LinkedHashMap<String,Object>(); output.put("action", a);
            if (!conversation.replyTo().isEmpty()) output.put("evidenceEventIds", List.of(conversation.replyTo().getFirst().eventId()));
            // Both old speech compatibility and the recommended compact output remain valid.
            if ("ai3".equals(s.personaId())) { output.put("speech", text(content)); output.put("memoryUpdates", Map.of()); }
            String response = json.writeValueAsString(output);
            List<String> captured = new ArrayList<>();
            when(client.chatCompletions(anyString(), anyLong(), anyString(), anyString(), anyList(), anyString(), anyInt())).thenAnswer(c -> {
                List<AiChatMessageDto> messages = c.getArgument(4); captured.add(messages.getLast().content());
                return new AiChatResult(response, "mock-only", 10, 10);
            });
            var generator = new AiTurnGenerator(client, properties, session.memory, safety);
            String before = json.writeValueAsString(o);
            var decision = generator.generate(session.rule, o, "conversation-" + s.id() + "-" + s.personaId());
            assertFalse(decision.fallback(), s.id() + ": " + decision.diagnostics());
            assertEquals(text(content), decision.speech());
            assertEquals(1, decision.diagnostics().get("calls"), "length/style must not trigger repair");
            assertEquals(before, json.writeValueAsString(o));
            var input = json.readTree(captured.getFirst());
            assertEquals(3, input.path("inputFormatVersion").asInt());
            assertEquals(json.valueToTree(o.persona()), input.at("/observation/persona"));
            assertEquals(json.valueToTree(conversation), input.path("conversation"));
            assertFalse(context.contains("message")); assertFalse(context.contains("content"));
            int eventCount = maps(session.state.getData().get("events")).size();
            session.rule.apply(session.state, session.actor, decision.action(), session.now);
            session.memory.commit(session.state, session.actor, decision, o, session.rule, eventCount);
            assertFalse(maps(session.memory.snapshot(session.state, session.actor).get("recentDecisions")).isEmpty());
            verify(client, times(1)).chatCompletions(anyString(), anyLong(), anyString(), eq("mock-only"), anyList(), anyString(), anyInt());
            var row = new LinkedHashMap<String,Object>(); row.put("scenarioId", s.id()); row.put("personaId", s.personaId());
            row.put("phase", o.phase()); row.put("round", o.round()); row.put("requiredEventId", s.requiredEventId());
            row.put("input", input); row.put("decision", decision); row.put("status", "MOCK_SUBMITTED");
            row.put("producedEventIds", maps(session.state.getData().get("events")).stream().skip(eventCount).map(e -> text(e.get("eventId"))).toList());
            var unreviewed = Map.of("status", "NOT_REVIEWED", "reason", "", "evidence", List.of());
            row.put("review", Map.of("interactionNeed", unreviewed, "proportionateLength", unreviewed, "stopsWhenComplete", unreviewed));
            samples.add(row);
        }
        assertEquals(4, guides.size());
        var report = MilestoneClosureScenarios.header("conversation-offline-20260922", "SYNTHETIC_TEST");
        report.put("evaluationSetVersion", "conversation-v1"); report.put("sampleCount", 48); report.put("samples", samples);
        report.put("realQualityVerified", false); report.put("L4Passed", false);
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/conversation-offline-evidence.json").toFile(), report);
    }
}
