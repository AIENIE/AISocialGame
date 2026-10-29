package com.aisocialgame.service.ai.v2;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.engine.v2.undercover.UndercoverRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local opt-in. Uses the configured model and persistent eight-call ceiling across reruns. */
@EnabledIfEnvironmentVariable(named = "UNDERCOVER_SELF_DESTRUCT_REAL", matches = "1")
@SpringBootTest(classes = AiSocialGameApplication.class, properties = {
        "spring.config.import=${AIENIE_APPLICATION_FILE}", "spring.jpa.hibernate.ddl-auto=validate",
        "app.game.scheduler-enabled=false", "app.ai.validation-call-limit=8",
        "app.ai.validation-run-id=undercover-self-destruct-20261021"
})
@ActiveProfiles("local")
class UndercoverSelfDestructRealTest {
    @Autowired AiTurnGenerator generator;
    @Autowired AiCallBudgetService budget;
    @Autowired AiModelConfigService models;
    @Autowired UndercoverRuleSet rules;
    @Autowired ObservationFactory observations;
    @Autowired BoundedAiClient bounded;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void collectOpeningDescriptionAndLateWordInferenceWithoutPrescribingTheDecision() throws Exception {
        assertEquals("local", System.getenv("ENV"));
        assertEquals(8, budget.limit()); assertEquals("undercover-self-destruct-20261021", budget.runId());
        bounded.requireReady();
        Path output = Path.of(Objects.requireNonNull(System.getenv("UNDERCOVER_SELF_DESTRUCT_EVIDENCE"))).toAbsolutePath().normalize();
        assertFalse(output.startsWith(Path.of("").toAbsolutePath().normalize().getParent()), "Evidence must remain outside the checkout");
        Map<String, Object> report = Files.exists(output) ? json.readValue(output.toFile(), Map.class) : new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>(maps(report.get("samples")));
        report.put("runId", budget.runId()); report.put("promptVersion", AiTurnGenerator.UNDERCOVER_PROMPT_VERSION);
        report.put("inputFormatVersion", AiPromptProjection.UNDERCOVER_VERSION);
        report.put("modelSelection", models.resolve("undercover", "p0").toMap());
        for (String id : List.of("opening", "late_word_inference")) {
            if (samples.stream().anyMatch(s -> id.equals(s.get("id")))) continue;
            assertTrue(budget.consumed() < 8, "The approved call allowance is exhausted");
            GameState state = scenario(id);
            var turn = rules.pendingTurns(state).stream().filter(t -> "p0".equals(t.actorId())).findFirst().orElseThrow();
            var observation = observations.build(state, rules, turn);
            Map<String, Object> sample = new LinkedHashMap<>(); sample.put("id", id);
            sample.put("input", AiPromptProjection.project(rules, observation));
            int before = budget.consumed();
            try {
                long remaining = state.getPhaseEndsAt() == null ? AiTurnGenerator.UNDERCOVER_DECISION_MILLIS
                        : Math.max(0, Duration.between(LocalDateTime.now(), state.getPhaseEndsAt()).toMillis() - 1_000);
                AiTurnDecision decision = generator.generate(rules, observation, budget.runId() + ":" + id, remaining);
                sample.put("action", decision.action()); sample.put("diagnostics", decision.diagnostics());
                assertFalse(decision.fallback()); assertTrue(generator.validate(rules, observation, decision).isEmpty());
                rules.apply(state, "p0", decision.action(), LocalDateTime.now());
                sample.put("applied", true); sample.put("phaseAfter", state.getPhase());
                sample.put("actorAlive", player(state, "p0").isAlive()); sample.put("winner", text(state.getData().get("winner")));
            } catch (AiTurnGenerator.GenerationFailure failure) {
                sample.put("failure", failure.getMessage()); sample.put("diagnostics", failure.diagnostics()); throw failure;
            } finally {
                sample.put("callsConsumed", budget.consumed() - before); samples.add(sample);
                report.put("callsConsumed", budget.consumed()); report.put("samples", samples);
                Files.createDirectories(output.getParent()); json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            }
        }
        assertEquals(2, samples.size()); assertTrue(budget.consumed() <= 8);
        assertTrue(samples.stream().allMatch(s -> Boolean.TRUE.equals(s.get("applied"))), "Report retains a failed scenario");
    }

    private GameState scenario(String id) {
        LocalDateTime now = LocalDateTime.now();
        Room room = new Room(UUID.randomUUID().toString(), "undercover", "定向真实模型验收", RoomStatus.WAITING, 6, false, null, "text",
                new LinkedHashMap<>(Map.of("wordPack", "custom", "speakTime", 0)));
        room.setHostUserId("host"); room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", "咖啡", "wordB", "绿茶")));
        for (int i = 0; i < 6; i++) room.getSeats().add(new RoomSeat(i, "p" + i, "玩家" + (i + 1), i == 0, null, "", true, false));
        GameState state = rules.initialize(room, now);
        state.getData().put("civilianWord", "绿茶"); state.getData().put("undercoverWord", "咖啡");
        for (int i = 0; i < 6; i++) {
            var p = player(state, "p" + i); p.setRole(i == 0 ? "UNDERCOVER" : "CIVILIAN"); p.setWord(i == 0 ? "咖啡" : "绿茶");
        }
        // Replace initialization events so the controlled actor never sees another assigned word or role.
        state.getData().put("events", new ArrayList<>());
        state.getData().put("descriptionOrder", state.getPlayers().stream().map(GamePlayerState::getPlayerId).toList());
        state.getData().put("descriptionIndex", 0);
        if ("opening".equals(id)) phase(state, "DESCRIPTION", 0, 0, now);
        else {
            state.setRoundNumber(1);
            for (String[] speech : List.of(new String[]{"p0", "忙碌时会想到它，有时为了提神。"}, new String[]{"p1", "通常用热水冲泡。"},
                    new String[]{"p2", "叶片在杯里慢慢舒展开来。"}, new String[]{"p3", "我更喜欢清爽、带一点苦味的口感。"}))
                event(state, "SPEAK", speech[0], null, speech[1], Map.of("content", speech[1]));
            state.setRoundNumber(2);
            for (String[] speech : List.of(new String[]{"p1", "不经过发酵，泡出来有嫩绿的颜色。"}, new String[]{"p2", "龙井和碧螺春是其中常见的品种。"}))
                event(state, "SPEAK", speech[0], null, speech[1], Map.of("content", speech[1]));
            state.setRoundNumber(3); state.getData().put("votes", new LinkedHashMap<>()); phase(state, "VOTING", null, 60, now);
        }
        return state;
    }
}
