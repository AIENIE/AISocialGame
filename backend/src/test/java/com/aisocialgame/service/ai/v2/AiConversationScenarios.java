package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Twelve focused scenarios. Every added utterance/verdict/ballot is a real rule submission.
 * Existing frozen initial states are reused, never rewritten. No generator/network is used here. */
final class AiConversationScenarios {
    record Scenario(String id, String personaId, ClosureSequenceRunner.Session session, VisibleObservation observation,
                    String expectedPhase, String expectedAction, String requiredEventId) {}
    private final MilestoneClosureScenarios fixtures = new MilestoneClosureScenarios();
    private final ClosureSequenceRunner runner = new ClosureSequenceRunner(fixtures);

    List<Scenario> load() throws Exception {
        List<Scenario> rows = new ArrayList<>();
        Map<String,Object> baselines = MilestoneClosureScenarios.JSON.readValue(ClosureSequenceRunner.BASELINE.toFile(), Map.class);
        for (var rule : fixtures.rules) for (int variant = 0; variant < 4; variant++) {
            var state = MilestoneClosureScenarios.JSON.convertValue(baselines.get(rule.gameId()), GameState.class);
            var session = runner.new Session(rule, state, PersonaPresets.all().getFirst(), null);
            var prepared = switch (rule.gameId()) {
                case "undercover" -> undercover(session, variant);
                case "werewolf" -> werewolf(session, variant);
                case "turtle_soup" -> soup(session, variant);
                default -> throw new IllegalStateException();
            };
            for (var persona : PersonaPresets.all()) {
                var copy = runner.new Session(rule, MilestoneClosureScenarios.JSON.convertValue(state, GameState.class), persona, null);
                copy.now = session.now;
                var turn = rule.pendingTurns(copy.state).stream().filter(t -> copy.actor.equals(t.actorId())).findFirst().orElseThrow();
                rows.add(new Scenario(prepared.id(), persona.getId(), copy, copy.observations.build(copy.state, rule, turn),
                        prepared.expectedPhase(), prepared.expectedAction(), prepared.requiredEventId()));
            }
        }
        return rows;
    }

    private Scenario undercover(ClosureSequenceRunner.Session s, int variant) {
        if (variant == 0) {
            reach(s, () -> "DESCRIPTION".equals(s.state.getPhase()) && has(s, "SPEAK"));
            return row(s, "first_description", "DESCRIPTION", "SPEAK", "");
        }
        if (variant < 3) {
            reach(s, () -> "CHALLENGE".equals(s.state.getPhase()) && asker(s).isPresent());
            String question = variant == 1 ? "你描述的使用场景具体指什么？" : "我更正刚才的说法，相似场景不一定是同一种用途。你怎么判断？";
            String event = s.submit(asker(s).orElseThrow(), action("ASK_PLAYER", question, s.actor), "SCRIPTED_PEER").getFirst();
            return row(s, variant == 1 ? "direct_question" : "peer_correction", "RESPONSE", "ANSWER_PLAYER", event);
        }
        reach(s, () -> "VOTING".equals(s.state.getPhase()));
        String other = s.state.getPlayers().stream().map(GamePlayerState::getPlayerId).filter(id -> !s.actor.equals(id)).findFirst().orElseThrow();
        s.submit(s.actor, action("VOTE", null, other), "SCRIPTED_SETUP");
        s.submit(other, action("VOTE", null, s.actor), "SCRIPTED_PEER");
        int index = 0;
        for (var p : s.state.getPlayers()) {
            if (Set.of(s.actor, other).contains(p.getPlayerId())) continue;
            var vote = index < 2 ? action("VOTE", null, s.actor) : index < 4 ? action("VOTE", null, other) : action("SKIP", null, null);
            s.submit(p.getPlayerId(), vote, "SCRIPTED_PEER"); index++;
        }
        reach(s, () -> "TIE_DEFENSE".equals(s.state.getPhase()) && has(s, "SPEAK"));
        String reveal = maps(s.state.getData().get("events")).stream().filter(e -> "VOTE_REVEAL".equals(e.get("type"))).map(e -> text(e.get("eventId"))).reduce((a,b) -> b).orElseThrow();
        return row(s, "runoff_defense", "TIE_DEFENSE", "SPEAK", reveal);
    }

    private Scenario werewolf(ClosureSequenceRunner.Session s, int variant) {
        if (variant == 0 || variant == 3) {
            reach(s, () -> "DAY_DISCUSS".equals(s.state.getPhase()) && has(s, "SPEAK"), variant == 3
                    ? "没有新的公开信息，我也暂时没有新增判断。" : "我暂时按发言相似性保留一种猜测，但没有确认阵营的新信息。");
            return row(s, variant == 0 ? "ordinary_stance" : "no_new_information", "DAY_DISCUSS", "SPEAK", "");
        }
        reach(s, () -> "DAY_INTERACTION".equals(s.state.getPhase()) && asker(s).isPresent());
        String question = variant == 1 ? "我声明自己是预言家，你对我这项身份声明怎么看？" : "我撤回把相似发言当作同阵营的说法，你仍保留原判断吗？";
        String event = s.submit(asker(s).orElseThrow(), action("ASK_PLAYER", question, s.actor), "SCRIPTED_PEER").getFirst();
        return row(s, variant == 1 ? "identity_question" : "counterevidence", "DAY_INTERACTION", "ANSWER_PLAYER", event);
    }

    private Scenario soup(ClosureSequenceRunner.Session s, int variant) {
        String human = s.state.getPlayers().stream().filter(p -> !p.isAi()).findFirst().orElseThrow().getPlayerId();
        var gold = fixtures.fixtures.turtleHostFixtures().stream().filter(f -> "midnight_train".equals(f.get("caseId")) && "ASK_QUESTION".equals(f.get("type"))).toList();
        String id, required;
        if (variant == 2) {
            var no = gold.stream().filter(f -> "NO".equals(f.get("expectedVerdict"))).findFirst().orElseThrow();
            s.submit(human, action("DISCUSS", "先把“" + text(no.get("content")) + "”作为待验证猜想。", null), "SCRIPTED_PEER");
            s.submit(human, action("ASK_QUESTION", text(no.get("content")), null), "SCRIPTED_PEER");
            required = s.host(gold).getFirst(); id = "verdict_excludes_hypothesis";
        } else {
            if (variant == 0) {
                s.submit(human, action("DISCUSS", "目前我们还没有裁决来区分猜想。", null), "SCRIPTED_PEER");
                s.submit(s.actor, action("DISCUSS", "我也先保留判断，等一个具体问题的裁决。", null), "SCRIPTED_SETUP");
            }
            String content = variant == 0 ? "没有新增信息，我也暂时没有要补充的。" : variant == 1
                    ? "下一问交给我，我已经想好要问什么了。" : "我们还没有确认时间线，请用一个具体问题推进。";
            required = s.submit(human, action("DISCUSS", content, null), "SCRIPTED_PEER").getFirst();
            id = List.of("no_new_contribution", "human_prepares_question", "unused", "specific_question").get(variant);
        }
        return row(s, id, "QUESTIONING", variant == 3 ? "ASK_QUESTION" : "DISCUSS", required);
    }

    private Scenario row(ClosureSequenceRunner.Session s, String id, String phase, String action, String required) {
        var turn = s.rule.pendingTurns(s.state).stream().filter(t -> s.actor.equals(t.actorId())).findFirst().orElseThrow();
        return new Scenario(s.rule.gameId() + ":" + id, s.persona.getId(), s, s.observations.build(s.state, s.rule, turn), phase, action, required);
    }
    private boolean has(ClosureSequenceRunner.Session s, String type) {
        return s.rule.legalActions(s.state, s.actor).stream().anyMatch(a -> type.equals(a.type()));
    }
    private Optional<String> asker(ClosureSequenceRunner.Session s) {
        return s.state.getPlayers().stream().filter(p -> !p.isAi()).filter(p -> s.rule.legalActions(s.state, p.getPlayerId()).stream()
                .anyMatch(a -> "ASK_PLAYER".equals(a.type()) && a.targets().contains(s.actor))).map(GamePlayerState::getPlayerId).findFirst();
    }
    private void reach(ClosureSequenceRunner.Session s, BooleanSupplier arrived) {
        reach(s, arrived, "我暂时按发言相似性保留一种猜测，但没有确认阵营的新信息。");
    }
    private void reach(ClosureSequenceRunner.Session s, BooleanSupplier arrived, String peerSpeech) {
        for (int guard = 0; guard < 256; guard++) {
            s.rule.advance(s.state, s.now);
            if (arrived.getAsBoolean()) return;
            if ("SETTLEMENT".equals(s.state.getPhase())) throw new IllegalStateException("Scenario ended early");
            boolean moved = false;
            for (var p : s.state.getPlayers()) {
                var legal = s.rule.legalActions(s.state, p.getPlayerId());
                if (legal.isEmpty()) continue;
                // Preserve real public speech before the evaluated turn; skip optional interactions/night skills.
                var choice = Set.of("DESCRIPTION", "DAY_DISCUSS").contains(s.state.getPhase()) ? legal.getFirst()
                        : legal.stream().filter(a -> "SKIP".equals(a.type())).findFirst().orElse(legal.getFirst());
                String content = "undercover".equals(s.rule.gameId()) ? "我目前想到的是大家都熟悉的日常使用场景。"
                        : peerSpeech;
                var a = action(choice.type(), choice.maxLength() > 0 ? content : null, choice.targets().isEmpty() ? null : choice.targets().getFirst());
                a.setNightAction(choice.nightAction()); s.submit(p.getPlayerId(), a, "SCRIPTED_SETUP"); moved = true; break;
            }
            if (!moved) s.now = s.now.plusSeconds(61);
        }
        throw new IllegalStateException("Scenario did not reach required opportunity");
    }
}
