package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.ValidationResult;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.aisocialgame.service.ai.v2.AiConversationContext;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aisocialgame.engine.v2.RuleSupport.*;

/**
 * The project's version 2 werewolf rules. All state transitions are local and
 * deterministic given the saved seed, accepted actions and supplied clock.
 */
@Component
public class WerewolfRuleSet implements GameRuleSet {
    private static final String WOLF = "WEREWOLF";
    private static final String SEER = "SEER";
    private static final String WITCH = "WITCH";
    private static final String GUARD = "GUARD";
    private static final String HUNTER = "HUNTER";
    private static final String IDIOT = "IDIOT";
    private static final String VILLAGER = "VILLAGER";
    private static final String SKIP = "SKIP";
    private static final String ABSTAIN = "abstain";
    private static final int NIGHT_TURN_SECONDS = 30;
    private static final int INTERACTION_SECONDS = 60;
    private static final int HUMAN_PRIORITY_SECONDS = 10;
    private static final int ANSWER_SECONDS = 20;
    private static final Map<String, Object> KNOWLEDGE = loadKnowledge();

    @Override public com.aisocialgame.model.Game definition() {
        var game=WerewolfDefinition.metadata(); game.setRuleVersion(2); game.setEngineBacked(true);
        game.setPhaseDefinitions(WerewolfDefinition.phaseDefinitions());
        var roles=new ArrayList<>(WerewolfDefinition.roleDefinitions());
        roles.add(new com.aisocialgame.engine.RoleDefinition(GUARD,"守卫","GOOD",true));
        roles.add(new com.aisocialgame.engine.RoleDefinition(IDIOT,"白痴","GOOD",false));game.setRoleDefinitions(roles);return com.aisocialgame.engine.v2.GameConfiguration.withDifficulty(game);
    }
    @Override public boolean isPlayerClaim(Map<String,Object> e) {return GameRuleSet.super.isPlayerClaim(e) || Set.of("VOTE_REVEALED","WOLF_COUNCIL").contains(text(e.get("type")));}
    @Override public Map<String,Object> confirmedFacts(Map<String,Object> e) {
        var d=map(e.get("data"));Map<String,Object> result=new LinkedHashMap<>();
        switch(text(e.get("type"))) {
            case "VOTE_CAST","VOTE_REVEALED" -> com.aisocialgame.service.ai.v2.AiInformationSources.ballot(e,d,result);
            case "DEATH" -> result.put("deadPlayerId",text(e.get("targetId")));
            case "IDIOT_REVEALED" -> {result.put("playerId",text(e.get("actorId")));result.put("exemptFromThisExile",true);result.put("canVote",false);com.aisocialgame.service.ai.v2.AiInformationSources.copy(d,result,"role");}
            case "SEER_CHECK" -> {result.put("targetPlayerId",text(e.get("targetId")));com.aisocialgame.service.ai.v2.AiInformationSources.copy(d,result,"result");}
            case "WOLF_COUNCIL","GUARD_ACTION","WITCH_ACTION" -> {result.put("actorId",text(e.get("actorId")));result.put("targetPlayerId",text(e.get("targetId")));com.aisocialgame.service.ai.v2.AiInformationSources.copy(d,result,"action");}
            case "NIGHT_SKIPPED" -> result.put("skippedBy",text(e.get("actorId")));
            case "ROLE_ASSIGNED" -> com.aisocialgame.service.ai.v2.AiInformationSources.copy(d,result,"role");
            case "PEACEFUL_NIGHT" -> result.put("noDeathsThisNight",true);
            default -> { }
        }
        return result;
    }
    @Override public Map<String,Object> privateConfirmed(Map<String,Object> facts) {
        Map<String,Object> result=new LinkedHashMap<>();
        com.aisocialgame.service.ai.v2.AiInformationSources.copy(facts,result,"canVote","wolfTeam","seerChecks","guardHistory","lastGuardTarget","antidoteRemaining","poisonRemaining","wolfTarget","hunterShotAvailable","idiotRevealed","myVote");return result;
    }

    @Override
    public String gameId() {
        return "werewolf";
    }

    @Override
    public ValidationResult validateStart(Room room) {
        int count = number(room.getConfig().get("playerCount"), room.getMaxPlayers());
        if (!List.of(6, 9, 12).contains(count)) return ValidationResult.invalid("狼人杀只支持6、9或12人板");
        if (room.getSeats().size() != count) return ValidationResult.invalid("请按所选人数坐满席位，可添加AI补位");
        if (room.getSeats().stream().map(s -> s.getPlayerId()).distinct().count() != count
                || room.getSeats().stream().map(s -> s.getSeatNumber()).distinct().count() != count) {
            return ValidationResult.invalid("玩家及座位不能重复");
        }
        if (!validOption(room, "template", "standard", Set.of("standard", "guard", "no_god"))
                || !validOption(room, "witchRule", "first_night", Set.of("no_save", "first_night", "always_save"))
                || !validOption(room, "winCondition", "side", Set.of("side", "city"))
                || !validOption(room, "hasLastWords", "first_night", Set.of("none", "first_night", "always"))) {
            return ValidationResult.invalid("狼人杀房间规则选项不支持");
        }
        if (!List.of(60, 90, 120).contains(number(room.getConfig().get("speechTime"), 120))) {
            return ValidationResult.invalid("发言时长须为60、90或120秒");
        }
        return ValidationResult.ok();
    }

    @Override
    public GameState initialize(Room room, LocalDateTime now) {
        ValidationResult validation = validateStart(room);
        require(validation.valid(), validation.message());
        GameState state = newState(room, now);
        state.getData().put("knowledgeVersion", KNOWLEDGE.get("knowledgeVersion"));
        Map<String, Object> rules = normalizedRules(room);
        state.getData().put("rules", rules);
        List<String> roles = new ArrayList<>();
        roleCounts(text(rules.get("template")), state.getPlayers().size())
                .forEach((role, count) -> roles.addAll(Collections.nCopies(count, role)));
        Collections.shuffle(roles, new Random(seed(state)));
        for (int i = 0; i < roles.size(); i++) state.getPlayers().get(i).setRole(roles.get(i));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("antidoteUsed", false);
        data.put("poisonUsed", false);
        data.put("seerChecks", new LinkedHashMap<>());
        data.put("guardHistory", new LinkedHashMap<>());
        data.put("guardLastTargets", new LinkedHashMap<>());
        data.put("revealedIdiots", new ArrayList<>());
        data.put("hunterUsed", new ArrayList<>());
        save(state, data);
        event(state, "GAME_STARTED", null, null, "狼人杀开始，死亡身份将在结算时揭晓。", Map.of("ruleVersion", 2));
        for (GamePlayerState player : state.getPlayers()) {
            event(state, "ROLE_ASSIGNED", player.getPlayerId(), null, "你的身份是" + roleName(player.getRole()),
                    Map.of("role", player.getRole()), "PRIVATE", List.of(player.getPlayerId()));
        }
        startNight(state, now, false);
        return state;
    }

    /** The complete, supported composition of a configured board. */
    public Map<String, Integer> roleCounts(String template, int count) {
        require(List.of(6, 9, 12).contains(count), "狼人杀只支持6、9或12人板");
        require(Set.of("standard", "guard", "no_god").contains(template), "板子类型不支持");
        Map<String, Integer> roles = new LinkedHashMap<>();
        roles.put(WOLF, count / 3);
        if (!"no_god".equals(template)) {
            roles.put(SEER, 1);
            if ("standard".equals(template)) {
                roles.put(WITCH, 1);
                if (count >= 9) roles.put(HUNTER, 1);
                if (count == 12) roles.put(IDIOT, 1);
            } else {
                roles.put(GUARD, 1);
                if (count >= 9) roles.put(WITCH, 1);
                if (count == 12) roles.put(HUNTER, 1);
            }
        }
        roles.put(VILLAGER, count - roles.values().stream().mapToInt(Integer::intValue).sum());
        return roles;
    }

    @Override
    public void apply(GameState state, String actorId, PlayerAction submitted, LocalDateTime now) {
        require(submitted != null, "缺少游戏动作");
        PlayerAction action = normalize(submitted);
        GamePlayerState actor = player(state, actorId);
        require(!expired(state, now), "行动窗口已结束", "PHASE_CHANGED");
        if ("DAY_VOTE".equals(state.getPhase())) {
            require(!map(state.getData().get("votes")).containsKey(actorId), "已完成投票", "ALREADY_ACTED");
        }
        if ("DAY_DISCUSS".equals(state.getPhase())) {
            require(Objects.equals(actor.getSeatNumber(), state.getCurrentSeat()), "当前不需要你发言", "NOT_YOUR_TURN");
        }
        LegalAction capability = matching(legalActions(state, actorId), action);
        require(capability != null, "当前阶段不允许该动作", "INVALID_ACTION");
        if (!capability.targets().isEmpty()) {
            require(capability.targets().contains(action.getTargetPlayerId()), "目标不在当前合法范围内");
        }
        if (capability.maxLength() > 0 && !text(action.getContent()).isBlank()) {
            require(text(action.getContent()).codePointCount(0, text(action.getContent()).length()) <= capability.maxLength(), "发言超过当前字数上限");
        }
        switch (state.getPhase()) {
            case "NIGHT" -> applyNight(state, actor, action, now);
            case "DAY_DISCUSS" -> applySpeech(state, actor, action, now);
            case "DAY_INTERACTION" -> applyInteraction(state, actor, action, now);
            case "DAY_VOTE" -> applyVote(state, actor, action, now);
            case "DEATH_ACTION" -> applyHunter(state, actor, action, now);
            case "LAST_WORDS" -> applyLastWords(state, actor, action, now);
            default -> throw bad("当前阶段不能行动");
        }
    }

    @Override
    public void advance(GameState state, LocalDateTime now) {
        if ("SETTLEMENT".equals(state.getPhase()) || !expired(state, now)) return;
        Map<String, Object> data = data(state);
        switch (state.getPhase()) {
            case "NIGHT" -> {
                String actorId = text(data.get("nightActor"));
                if (!actorId.isBlank()) applyNight(state, player(state, actorId), action(SKIP, "", null), now);
            }
            case "DAY_DISCUSS" -> {
                GamePlayerState speaker = currentSpeaker(state);
                if (speaker != null) applySpeech(state, speaker, action(SKIP, "", null), now);
            }
            case "DAY_INTERACTION" -> advanceInteraction(state, now);
            case "DAY_VOTE" -> resolveVoting(state, now);
            case "DEATH_ACTION" -> applyHunter(state, player(state, text(data.get("deathActor"))), action(SKIP, "", null), now);
            case "LAST_WORDS" -> applyLastWords(state, player(state, text(data.get("lastWordsActor"))), action(SKIP, "", null), now);
            default -> { }
        }
    }

    @Override
    public void onAiFailure(GameState state, TurnRequest turn, LocalDateTime now) {
        if (!isTurnCurrent(state, turn)) return;
        GamePlayerState actor = player(state, turn.actorId());
        PlayerAction skip = action(SKIP, "", null);
        // Recovery closes this actor's opportunity, even if its deadline elapsed during the call.
        // In a simultaneous ballot it records only this player's abstention, never everyone else's.
        switch (state.getPhase()) {
            case "NIGHT" -> applyNight(state, actor, skip, now);
            case "DAY_DISCUSS" -> applySpeech(state, actor, skip, now);
            case "DAY_INTERACTION" -> applyInteraction(state, actor, skip, now);
            case "DAY_VOTE" -> applyVote(state, actor, skip, now);
            case "DEATH_ACTION" -> applyHunter(state, actor, skip, now);
            case "LAST_WORDS" -> applyLastWords(state, actor, skip, now);
            default -> throw new IllegalStateException("No werewolf AI opportunity to close");
        }
    }

    @Override
    public List<LegalAction> legalActions(GameState state, String actorId) {
        GamePlayerState actor = state.getPlayers().stream().filter(p -> Objects.equals(p.getPlayerId(), actorId)).findFirst().orElse(null);
        if (actor == null || "SETTLEMENT".equals(state.getPhase())) return List.of();
        Map<String, Object> data = data(state);
        if ("DEATH_ACTION".equals(state.getPhase())) {
            if (!Objects.equals(actorId, data.get("deathActor"))) return List.of();
            return List.of(LegalAction.target("HUNTER_SHOOT", "开枪", aliveIds(state, actorId), 120), LegalAction.simple(SKIP, "放弃开枪"));
        }
        if ("LAST_WORDS".equals(state.getPhase())) {
            return Objects.equals(actorId, data.get("lastWordsActor"))
                    ? List.of(LegalAction.text("SPEAK", "留下遗言", 120), LegalAction.simple(SKIP, "结束遗言")) : List.of();
        }
        if (!actor.isAlive()) return List.of();
        return switch (state.getPhase()) {
            case "NIGHT" -> Objects.equals(actorId, data.get("nightActor")) ? nightActions(state, actor, data) : List.of();
            case "DAY_DISCUSS" -> Objects.equals(actor.getSeatNumber(), state.getCurrentSeat())
                    ? List.of(LegalAction.text("SPEAK", "结束发言", 300), LegalAction.simple(SKIP, "跳过发言")) : List.of();
            case "DAY_INTERACTION" -> interactionActions(state, actor, data);
            case "DAY_VOTE" -> {
                if (strings(data.get("revealedIdiots")).contains(actorId) || map(data.get("votes")).containsKey(actorId)) yield List.of();
                List<String> targets = aliveIds(state, actorId).stream().filter(id -> !strings(data.get("revealedIdiots")).contains(id)).toList();
                List<LegalAction> actions = new ArrayList<>();
                if (!targets.isEmpty()) actions.add(LegalAction.target("VOTE", "投票放逐", targets, 120));
                actions.add(LegalAction.simple(SKIP, "弃票"));
                yield List.copyOf(actions);
            }
            default -> List.of();
        };
    }

    @Override
    public List<TurnRequest> pendingTurns(GameState state) {
        List<GamePlayerState> candidates = state.getPlayers().stream().filter(p -> automated(p) && !legalActions(state, p.getPlayerId()).isEmpty())
                .sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber)).toList();
        if (candidates.isEmpty()) return List.of();
        Map<String, Object> data = data(state);
        if ("DAY_INTERACTION".equals(state.getPhase()) && text(data.get("questionTarget")).isBlank()) {
            if (!flag(data.get("humanPriorityElapsed"))) return List.of();
            candidates = candidates.stream().filter(p -> !strings(data.get("askedPlayers")).contains(p.getPlayerId())).limit(1).toList();
        }
        return candidates.stream().map(p -> turn(state, p.getPlayerId(), turnKind(state, data),
                "DAY_VOTE".equals(state.getPhase()) ? "ballot" : "")).toList();
    }

    @Override
    public Map<String, Object> publicData(GameState state) {
        Map<String, Object> data = data(state);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleVersion", 2);
        result.put("rules", map(state.getData().get("rules")));
        result.put("roleCounts", map(map(state.getData().get("rules")).get("roleCounts")));
        result.put("revealedIdiots", strings(data.get("revealedIdiots")));
        result.put("lastNightDeaths", strings(data.get("lastNightDeaths")));
        if ("DAY_INTERACTION".equals(state.getPhase())) {
            Map<String, Object> interaction = new LinkedHashMap<>();
            interaction.put("completedPairs", number(data.get("interactionPairs"), 0));
            interaction.put("maxPairs", 2);
            interaction.put("questionActor", text(data.get("questionActor")));
            interaction.put("questionTarget", text(data.get("questionTarget")));
            interaction.put("question", text(data.get("question")));
            interaction.put("questionEventId", text(data.get("questionEventId")));
            interaction.put("humanPriority", !flag(data.get("humanPriorityElapsed")));
            result.put("interaction", interaction);
        }
        return result;
    }

    @Override
    public Map<String, Object> privateData(GameState state, String actorId) {
        GamePlayerState actor = state.getPlayers().stream().filter(p -> Objects.equals(p.getPlayerId(), actorId)).findFirst().orElse(null);
        if (actor == null) return Map.of();
        Map<String, Object> data = data(state);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canVote", actor.isAlive() && !strings(data.get("revealedIdiots")).contains(actorId));
        if (WOLF.equals(actor.getRole())) {
            result.put("wolfTeam", wolfIds(state));
            if (actor.isAlive()) result.put("wolfCouncil", maps(data.get("wolfCouncil")));
        }
        if (SEER.equals(actor.getRole())) result.put("seerChecks", maps(map(data.get("seerChecks")).get(actorId)));
        if (GUARD.equals(actor.getRole())) {
            result.put("guardHistory", maps(map(data.get("guardHistory")).get(actorId)));
            result.put("lastGuardTarget", text(map(data.get("guardLastTargets")).get(actorId)));
        }
        if (WITCH.equals(actor.getRole())) {
            result.put("antidoteRemaining", flag(data.get("antidoteUsed")) ? 0 : 1);
            result.put("poisonRemaining", flag(data.get("poisonUsed")) ? 0 : 1);
            if (actor.isAlive() && "NIGHT".equals(state.getPhase()) && Objects.equals(actorId, data.get("nightActor")) && !flag(data.get("antidoteUsed"))) {
                result.put("wolfTarget", text(data.get("wolfTarget")));
            }
        }
        if (HUNTER.equals(actor.getRole())) result.put("hunterShotAvailable", Objects.equals(actorId, data.get("deathActor")) && "DEATH_ACTION".equals(state.getPhase()));
        if (IDIOT.equals(actor.getRole())) result.put("idiotRevealed", strings(data.get("revealedIdiots")).contains(actorId));
        if ("NIGHT".equals(state.getPhase()) && Objects.equals(actorId, data.get("nightActor"))) {
            result.put("nightStep", actor.getRole());
            result.put("actionEndsAt", state.getPhaseEndsAt() == null ? "" : state.getPhaseEndsAt().toString());
        }
        if ("DAY_VOTE".equals(state.getPhase()) && map(data.get("votes")).containsKey(actorId)) {
            result.put("myVote", map(data.get("votes")).get(actorId));
        }
        return result;
    }

    @Override
    public String visibleRole(GameState state, GamePlayerState candidate, String viewerId) {
        if ("SETTLEMENT".equals(state.getPhase()) || Objects.equals(candidate.getPlayerId(), viewerId)) return candidate.getRole();
        if (strings(data(state).get("revealedIdiots")).contains(candidate.getPlayerId())) return IDIOT;
        GamePlayerState viewer = state.getPlayers().stream().filter(p -> Objects.equals(p.getPlayerId(), viewerId)).findFirst().orElse(null);
        return viewer != null && WOLF.equals(viewer.getRole()) && WOLF.equals(candidate.getRole()) ? WOLF : null;
    }

    @Override
    public AiConversationContext conversation(VisibleObservation o) {
        return AiConversationContext.create(o.phase(), o, a -> switch (a.type()) {
            case "VOTE" -> AiConversationContext.guide("遵循现有投票公开规则，不强加公开解释。", "VOTE");
            case "SKIP" -> switch (o.phase()) {
                case "DAY_VOTE" -> AiConversationContext.guide("此动作是弃票，不是让出发言。", "ABSTAIN");
                case "NIGHT" -> AiConversationContext.guide("此动作是跳过本次夜间技能，不是让出发言。", "NIGHT_SKIP");
                case "DEATH_ACTION" -> AiConversationContext.guide("此动作是放弃本次技能。", "DECLINE_SKILL");
                default -> AiConversationContext.guide("本次不发言；不附带一段补充。", "YIELD");
            };
            case "ASK_PLAYER" -> AiConversationContext.guide("通常一个具体问题，不把质询写成整轮总结。", "QUESTION");
            case "ANSWER_PLAYER" -> AiConversationContext.guide("通常一至两句；身份对抗、关键辩解或重要改判可在合法上限内展开。", "CLARIFY", "STATE_POSITION", "REVISE");
            case "SPEAK" -> AiConversationContext.guide("通常两至三句，表达判断及最关键依据；重要辩解、身份对抗或改判可在合法上限内展开。", "STATE_POSITION", "CLARIFY", "REVISE", "CONTRIBUTE");
            case "NIGHT_ACTION" -> AiConversationContext.guide("按夜间技能的现有私密范围决定行动，无须额外公开解释。", "USE_SKILL");
            case "HUNTER_SHOOT" -> AiConversationContext.guide("按猎人技能的现有公开规则决定行动，不强加公开解释。", "USE_SKILL");
            default -> AiConversationContext.guide("按合法行动参与。", "CHOOSE_FOR_SITUATION");
        }, Set.of("DAY_INTERACTION"), Set.of("ANSWER_PLAYER", "ANSWER_SKIPPED"));
    }

    @Override
    public String instruction(VisibleObservation observation) {
        return "你是认真参加狼人杀的桌游朋友。只依据当前观察和合法动作，一次决定游戏行动、对外发言与克制的神态。"
                + "事实、其他玩家的身份声明和自己的猜测必须区分；死者在结算前不翻牌。"
                + "保持自己的发言、投票承诺和假身份时间线连续；新证据可以改变观点，也可以承认误会。"
                + "别人点名你时回应具体问题，不要总说先观察，不必每次都带情绪动作。"
                + ("NIGHT".equals(observation.phase())
                ? "当前是私密夜晚行动；狼人可以在私密建议里回应队友，其余角色只向自己说明选择。只选一个合法行动，SKIP也是认真权衡后的选择。"
                : "当前发言是公开的。自己的身份和查验可自愿作出玩家声明，不能把私密记录编号或他人的不可见信息写进公开文本。")
                + "引用依据使用观察中的eventId；任何关系或情绪更新只能针对本局本人，不能修改系统真值。";
    }

    @Override
    public List<String> knowledge(VisibleObservation observation) {
        List<String> result = new ArrayList<>();
        Map<String, Object> rules = map(KNOWLEDGE.get("rules"));
        result.add("知识版本：" + KNOWLEDGE.get("knowledgeVersion") + "；实际板子与可执行动作以本局观察为准。");
        result.addAll(strings(rules.get("information")));
        String role = text(observation.self().get("role"));
        Map<String, Object> roleKnowledge = map(map(KNOWLEDGE.get("roles")).get(role));
        if (!roleKnowledge.isEmpty()) {
            result.add(text(roleKnowledge.get("goal")));
            result.addAll(strings(roleKnowledge.get("principles")));
            result.add("避免：" + String.join("；", strings(roleKnowledge.get("avoid"))));
        }
        result.addAll(strings(rules.get("NIGHT".equals(observation.phase()) ? "night" : "day")));
        if ("DEATH_ACTION".equals(observation.phase()) || "LAST_WORDS".equals(observation.phase())) result.addAll(strings(rules.get("deathSkills")));
        result.addAll(strings(KNOWLEDGE.get("expressionGuidance")));
        return List.copyOf(result);
    }

    @Override
    public PlayerAction fallback(VisibleObservation observation) {
        List<LegalAction> legal = observation.legalActions();
        if (legal.isEmpty()) return action(SKIP, "", null);
        String role = text(observation.self().get("role"));
        if ("NIGHT".equals(observation.phase())) {
            if (WITCH.equals(role)) {
                String knife = text(observation.privateFacts().get("wolfTarget"));
                LegalAction heal = legal.stream().filter(a -> "WITCH_SAVE".equals(a.nightAction()) && a.targets().contains(observation.actorId())).findFirst().orElse(null);
                if (heal != null && observation.actorId().equals(knife)) return nightAction("WITCH_SAVE", knife, "", true);
                return action(SKIP, "", null);
            }
            LegalAction option = legal.stream().filter(a -> "NIGHT_ACTION".equals(a.type()) && !a.targets().isEmpty()).findFirst().orElse(null);
            if (option == null) return action(SKIP, "", null);
            List<String> targets = new ArrayList<>(option.targets());
            if (SEER.equals(role)) {
                Set<String> checked = maps(observation.privateFacts().get("seerChecks")).stream().map(c -> text(c.get("targetPlayerId"))).collect(Collectors.toSet());
                List<String> fresh = targets.stream().filter(id -> !checked.contains(id)).toList();
                if (!fresh.isEmpty()) targets = fresh;
            }
            return nightAction(option.nightAction(), choose(observation, targets), "", false);
        }
        if ("DEATH_ACTION".equals(observation.phase())) return action(SKIP, "", null);
        if ("DAY_INTERACTION".equals(observation.phase())) {
            LegalAction answer = legal.stream().filter(a -> "ANSWER_PLAYER".equals(a.type())).findFirst().orElse(null);
            if (answer != null) return action("ANSWER_PLAYER", "你问的这一点，我还不能确定。先保留判断，等有具体依据再回应。", answer.targets().get(0));
            return action(SKIP, "", null);
        }
        if ("DAY_VOTE".equals(observation.phase())) {
            LegalAction vote = legal.stream().filter(a -> "VOTE".equals(a.type())).findFirst().orElse(null);
            return vote == null ? action(SKIP, "", null) : action("VOTE", "信息还不充分，这是我暂时的判断。", choose(observation, vote.targets()));
        }
        if ("LAST_WORDS".equals(observation.phase())) return action("SPEAK", "我先退场了。请把前面的说法和投票对照着看，别只凭谁说得笃定就下结论。", null);
        String other = lastSpeakerName(observation);
        String content = other.isBlank()
                ? "我先把判断留一点余地。大家可以说说最在意哪一句发言，我们把依据对清楚再投票。"
                : "我想接着" + other + "刚才的说法问一句：主要依据是哪一点？我现在还不想只凭语气给人定身份。";
        return action("SPEAK", content, null);
    }

    @Override
    public List<String> validateDecision(VisibleObservation observation, AiTurnDecision decision) {
        if (decision.fallback()) return List.of();
        List<String> errors = new ArrayList<>();
        if ("WITCH_SAVE".equalsIgnoreCase(decision.action().getNightAction()) && !decision.action().isUseHeal()) {
            errors.add("使用解药必须同时设置useHeal=true；决定留药时请选择SKIP。");
        }
        String spoken = text(decision.speech()) + " " + text(decision.action().getContent());
        if (!"SETTLEMENT".equals(observation.phase()) && (spoken.contains("系统确认你是") || spoken.contains("系统已证实你是") || spoken.contains("死亡后翻牌显示"))) {
            errors.add("尚未结算，玩家的身份推断不能冒充系统确认或死亡翻牌。");
        }
        if ("ANSWER_PLAYER".equalsIgnoreCase(decision.action().getType())) {
            List<String> questions = observation.events().stream()
                    .filter(e -> "ASK_PLAYER".equals(text(e.get("type"))) && observation.actorId().equals(text(e.get("targetId"))))
                    .map(e -> text(e.get("eventId"))).filter(id -> !id.isBlank()).toList();
            if (!questions.isEmpty() && !decision.evidenceEventIds().contains(questions.get(questions.size() - 1))) {
                errors.add("定向回答需要引用刚才向你提出的问题eventId。");
            }
        }
        return errors;
    }

    private void startNight(GameState state, LocalDateTime now, boolean nextRound) {
        if (nextRound) state.setRoundNumber(state.getRoundNumber() + 1);
        Map<String, Object> data = data(state);
        data.put("wolfVotes", new LinkedHashMap<>());
        data.put("wolfCouncil", new ArrayList<>());
        data.put("wolfTarget", "");
        data.put("guardTarget", "");
        data.put("saveTarget", "");
        data.put("poisonTarget", "");
        data.put("lastNightDeaths", new ArrayList<>());
        data.put("nightIndex", 0);
        List<String> queue = new ArrayList<>();
        for (String role : List.of(WOLF, GUARD, SEER, WITCH)) {
            if (WITCH.equals(role) && flag(data.get("antidoteUsed")) && flag(data.get("poisonUsed"))) continue;
            alive(state).stream().filter(p -> role.equals(p.getRole())).forEach(p -> queue.add(p.getPlayerId()));
        }
        data.put("nightQueue", queue);
        data.put("nightActor", queue.isEmpty() ? "" : queue.get(0));
        save(state, data);
        phase(state, "NIGHT", null, NIGHT_TURN_SECONDS, now);
        event(state, "NIGHT_STARTED", null, null, "天黑了，第" + state.getRoundNumber() + "夜开始。", Map.of());
        if (queue.isEmpty()) resolveNight(state, now);
    }

    private List<LegalAction> nightActions(GameState state, GamePlayerState actor, Map<String, Object> data) {
        List<LegalAction> actions = new ArrayList<>();
        switch (actor.getRole()) {
            case WOLF -> {
                List<String> targets = alive(state).stream().filter(p -> !WOLF.equals(p.getRole())).map(GamePlayerState::getPlayerId).toList();
                if (!targets.isEmpty()) actions.add(LegalAction.night("WOLF_KILL", "建议并选择刀口", targets));
            }
            case SEER -> {
                List<String> targets = aliveIds(state, actor.getPlayerId());
                if (!targets.isEmpty()) actions.add(LegalAction.night("SEER_CHECK", "查验玩家", targets));
            }
            case GUARD -> {
                String last = text(map(data.get("guardLastTargets")).get(actor.getPlayerId()));
                List<String> targets = alive(state).stream().map(GamePlayerState::getPlayerId).filter(id -> !id.equals(last)).toList();
                if (!targets.isEmpty()) actions.add(LegalAction.night("GUARD_PROTECT", "守护玩家", targets));
            }
            case WITCH -> {
                String knife = text(data.get("wolfTarget"));
                if (!flag(data.get("antidoteUsed")) && !knife.isBlank() && canSelfSave(state, actor.getPlayerId(), knife)) {
                    actions.add(LegalAction.night("WITCH_SAVE", "使用解药", List.of(knife)));
                }
                List<String> targets = aliveIds(state, actor.getPlayerId());
                if (!flag(data.get("poisonUsed")) && !targets.isEmpty()) actions.add(LegalAction.night("WITCH_POISON", "使用毒药", targets));
            }
            default -> { }
        }
        actions.add(LegalAction.simple(SKIP, "本夜不行动"));
        return actions;
    }

    private void applyNight(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        Map<String, Object> data = data(state);
        String id = actor.getPlayerId();
        String kind = SKIP.equals(action.getType()) ? SKIP : text(action.getNightAction());
        String target = text(action.getTargetPlayerId());
        String note = text(action.getContent()).strip();
        List<String> audience = WOLF.equals(actor.getRole()) ? wolfIds(state) : List.of(id);
        switch (actor.getRole()) {
            case WOLF -> {
                Map<String, Object> votes = map(data.get("wolfVotes"));
                votes.put(id, SKIP.equals(kind) ? ABSTAIN : target);
                data.put("wolfVotes", votes);
                event(state, "WOLF_COUNCIL", id, SKIP.equals(kind) ? null : target,
                        actor.getDisplayName() + (SKIP.equals(kind) ? "建议今晚空刀" : "提出了今晚的刀口建议") + (note.isBlank() ? "。" : "：" + note),
                        Map.of("action", kind), "PRIVATE", audience);
                List<Map<String, Object>> council = maps(data.get("wolfCouncil"));
                Map<String, Object> suggestion = new LinkedHashMap<>();
                suggestion.put("eventId", lastEventId(state));
                suggestion.put("actorId", id);
                suggestion.put("targetPlayerId", SKIP.equals(kind) ? "" : target);
                suggestion.put("content", note);
                council.add(suggestion);
                data.put("wolfCouncil", council);
                if (votes.size() == alive(state).stream().filter(p -> WOLF.equals(p.getRole())).count()) data.put("wolfTarget", wolfConsensus(state, votes));
            }
            case GUARD -> {
                String protectedId = SKIP.equals(kind) ? "" : target;
                data.put("guardTarget", protectedId);
                Map<String, Object> last = map(data.get("guardLastTargets"));
                last.put(id, protectedId);
                data.put("guardLastTargets", last);
                Map<String, Object> allHistory = map(data.get("guardHistory"));
                List<Map<String, Object>> history = maps(allHistory.get(id));
                history.add(Map.of("round", state.getRoundNumber(), "targetPlayerId", protectedId));
                allHistory.put(id, history);
                data.put("guardHistory", allHistory);
                event(state, "GUARD_ACTION", id, protectedId.isBlank() ? null : protectedId,
                        SKIP.equals(kind) ? "你本夜没有守护。" : "你已选择本夜守护目标。", Map.of("action", kind), "PRIVATE", audience);
            }
            case SEER -> {
                if (!SKIP.equals(kind)) {
                    String result = WOLF.equals(player(state, target).getRole()) ? "WOLF" : "GOOD";
                    event(state, "SEER_CHECK", id, target, "本次查验结果：" + ("WOLF".equals(result) ? "狼人" : "好人"), Map.of("result", result), "PRIVATE", audience);
                    Map<String, Object> allChecks = map(data.get("seerChecks"));
                    List<Map<String, Object>> checks = maps(allChecks.get(id));
                    checks.add(Map.of("round", state.getRoundNumber(), "targetPlayerId", target, "result", result, "eventId", lastEventId(state)));
                    allChecks.put(id, checks);
                    data.put("seerChecks", allChecks);
                } else event(state, "NIGHT_SKIPPED", id, null, "你本夜没有查验。", Map.of(), "PRIVATE", audience);
            }
            case WITCH -> {
                if ("WITCH_SAVE".equals(kind)) {
                    data.put("saveTarget", text(data.get("wolfTarget")));
                    data.put("antidoteUsed", true);
                } else if ("WITCH_POISON".equals(kind)) {
                    data.put("poisonTarget", target);
                    data.put("poisonUsed", true);
                }
                event(state, "WITCH_ACTION", id, SKIP.equals(kind) ? null : target,
                        SKIP.equals(kind) ? "你本夜选择留药。" : "你本夜已使用一瓶药。", Map.of("action", kind), "PRIVATE", audience);
            }
            default -> throw bad("该身份没有夜晚行动");
        }
        List<String> queue = strings(data.get("nightQueue"));
        int next = number(data.get("nightIndex"), 0) + 1;
        data.put("nightIndex", next);
        data.put("nightActor", next < queue.size() ? queue.get(next) : "");
        save(state, data);
        if (next >= queue.size()) resolveNight(state, now);
        else phase(state, "NIGHT", null, NIGHT_TURN_SECONDS, now);
    }

    private void resolveNight(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        String knife = text(data.get("wolfTarget"));
        String guard = text(data.get("guardTarget"));
        String heal = text(data.get("saveTarget"));
        String poison = text(data.get("poisonTarget"));
        Map<String, String> deaths = new LinkedHashMap<>();
        if (!knife.isBlank()) {
            boolean guarded = knife.equals(guard);
            boolean saved = knife.equals(heal);
            if (guarded == saved) deaths.put(knife, "WOLF_KILL");
        }
        if (!poison.isBlank()) deaths.put(poison, "POISON");
        data.put("lastNightDeaths", new ArrayList<>(deaths.keySet()));
        data.put("nightActor", "");
        save(state, data);
        if (deaths.isEmpty()) event(state, "PEACEFUL_NIGHT", null, null, "天亮了，昨夜无人死亡。", Map.of());
        beginDeaths(state, deaths, true, "DAY_DISCUSS", now);
    }

    private void startDiscussion(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        data.put("spokenPlayers", new ArrayList<>());
        save(state, data);
        GamePlayerState first = alive(state).stream().findFirst().orElse(null);
        if (first == null) { finishIfWon(state); return; }
        phase(state, "DAY_DISCUSS", first.getSeatNumber(), speechSeconds(state), now);
        event(state, "DAY_STARTED", null, null, "第" + state.getRoundNumber() + "天，按座位开始讨论。", Map.of());
    }

    private void applySpeech(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        if (!SKIP.equals(action.getType())) {
            String content = requireContent(action, 300);
            event(state, "SPEECH", actor.getPlayerId(), null, actor.getDisplayName() + "：" + content, Map.of("statement", "PLAYER_CLAIM", "content", content));
        } else event(state, "SPEECH_SKIPPED", actor.getPlayerId(), null, actor.getDisplayName() + "结束了本轮发言。", Map.of());
        Map<String, Object> data = data(state);
        List<String> spoken = new ArrayList<>(strings(data.get("spokenPlayers")));
        spoken.add(actor.getPlayerId());
        data.put("spokenPlayers", spoken);
        save(state, data);
        GamePlayerState next = alive(state).stream().filter(p -> !spoken.contains(p.getPlayerId())).findFirst().orElse(null);
        if (next == null) startInteraction(state, now);
        else phase(state, "DAY_DISCUSS", next.getSeatNumber(), speechSeconds(state), now);
    }

    private void startInteraction(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        data.put("interactionPairs", 0);
        data.put("askedPlayers", new ArrayList<>());
        data.put("answeredPlayers", new ArrayList<>());
        data.put("questionActor", "");
        data.put("questionTarget", "");
        data.put("question", "");
        data.put("questionEventId", "");
        data.put("interactionEndsAt", now.plusSeconds(INTERACTION_SECONDS).toString());
        boolean humans = alive(state).stream().anyMatch(p -> !automated(p));
        data.put("humanPriorityElapsed", !humans);
        save(state, data);
        phase(state, "DAY_INTERACTION", null, humans ? HUMAN_PRIORITY_SECONDS : INTERACTION_SECONDS, now);
        event(state, "INTERACTION_STARTED", null, null, "可以进行最多两组定向问答，也可以直接跳过。", Map.of("maxPairs", 2));
    }

    private List<LegalAction> interactionActions(GameState state, GamePlayerState actor, Map<String, Object> data) {
        String target = text(data.get("questionTarget"));
        if (!target.isBlank()) {
            return target.equals(actor.getPlayerId())
                    ? List.of(LegalAction.target("ANSWER_PLAYER", "回答问题", List.of(text(data.get("questionActor"))), 120), LegalAction.simple(SKIP, "暂不回答")) : List.of();
        }
        if (number(data.get("interactionPairs"), 0) >= 2 || strings(data.get("askedPlayers")).contains(actor.getPlayerId())) return List.of();
        if (automated(actor) && !flag(data.get("humanPriorityElapsed"))) return List.of();
        List<String> targets = aliveIds(state, actor.getPlayerId()).stream().filter(id -> !strings(data.get("answeredPlayers")).contains(id)).toList();
        if (targets.isEmpty()) return List.of(LegalAction.simple(SKIP, "跳过质询"));
        return List.of(LegalAction.target("ASK_PLAYER", "向玩家提问", targets, 60), LegalAction.simple(SKIP, "跳过质询"));
    }

    private void applyInteraction(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        Map<String, Object> data = data(state);
        if (!text(data.get("questionTarget")).isBlank()) {
            if (!SKIP.equals(action.getType())) {
                event(state, "ANSWER_PLAYER", actor.getPlayerId(), text(data.get("questionActor")), actor.getDisplayName() + "：" + requireContent(action, 120),
                        Map.of("questionEventId", text(data.get("questionEventId")), "statement", "PLAYER_CLAIM", "content", requireContent(action, 120)));
            } else event(state, "ANSWER_SKIPPED", actor.getPlayerId(), text(data.get("questionActor")), actor.getDisplayName() + "暂时没有补充。", Map.of());
            List<String> answered = new ArrayList<>(strings(data.get("answeredPlayers")));
            answered.add(actor.getPlayerId());
            data.put("answeredPlayers", answered);
            data.put("interactionPairs", number(data.get("interactionPairs"), 0) + 1);
            data.put("questionActor", "");
            data.put("questionTarget", "");
            data.put("question", "");
            data.put("questionEventId", "");
            save(state, data);
            if (number(data.get("interactionPairs"), 0) >= 2 || !now.isBefore(interactionEnd(data))) startVoting(state, now);
            else {
                phase(state, "DAY_INTERACTION", null, remainingSeconds(now, interactionEnd(data)), now);
                if (noRemainingQuestions(state)) startVoting(state, now);
            }
            return;
        }
        List<String> asked = new ArrayList<>(strings(data.get("askedPlayers")));
        asked.add(actor.getPlayerId());
        data.put("askedPlayers", asked);
        if (SKIP.equals(action.getType())) {
            save(state, data);
            if (!flag(data.get("humanPriorityElapsed")) && alive(state).stream().filter(p -> !automated(p))
                    .allMatch(p -> strings(data.get("askedPlayers")).contains(p.getPlayerId()))) {
                data.put("humanPriorityElapsed", true);
                save(state, data);
                phase(state, "DAY_INTERACTION", null, remainingSeconds(now, interactionEnd(data)), now);
            }
            if (noRemainingQuestions(state)) startVoting(state, now);
            return;
        }
        String question = requireContent(action, 60);
        String target = action.getTargetPlayerId();
        event(state, "ASK_PLAYER", actor.getPlayerId(), target, actor.getDisplayName() + "向" + player(state, target).getDisplayName() + "提问：" + question, Map.of("statement", "PLAYER_CLAIM", "content", question));
        data.put("questionActor", actor.getPlayerId());
        data.put("questionTarget", target);
        data.put("question", question);
        data.put("questionEventId", lastEventId(state));
        data.put("humanPriorityElapsed", true);
        save(state, data);
        phase(state, "DAY_INTERACTION", player(state, target).getSeatNumber(), Math.min(ANSWER_SECONDS, remainingSeconds(now, interactionEnd(data))), now);
    }

    private void advanceInteraction(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        if (!text(data.get("questionTarget")).isBlank()) {
            applyInteraction(state, player(state, text(data.get("questionTarget"))), action(SKIP, "", text(data.get("questionActor"))), now);
        } else if (!now.isBefore(interactionEnd(data))) {
            startVoting(state, now);
        } else {
            data.put("humanPriorityElapsed", true);
            save(state, data);
            phase(state, "DAY_INTERACTION", null, remainingSeconds(now, interactionEnd(data)), now);
            if (noRemainingQuestions(state)) startVoting(state, now);
        }
    }

    private boolean noRemainingQuestions(GameState state) {
        Map<String, Object> data = data(state);
        return alive(state).stream().noneMatch(p -> !strings(data.get("askedPlayers")).contains(p.getPlayerId())
                && aliveIds(state, p.getPlayerId()).stream().anyMatch(id -> !strings(data.get("answeredPlayers")).contains(id)));
    }

    private void startVoting(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        data.put("votes", new LinkedHashMap<>());
        data.put("voteNotes", new LinkedHashMap<>());
        save(state, data);
        phase(state, "DAY_VOTE", null, 30, now);
        event(state, "VOTING_STARTED", null, null, "开始投票，所有票型将在截止后同时公开。", Map.of());
    }

    private void applyVote(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        Map<String, Object> data = data(state);
        Map<String, Object> votes = map(data.get("votes"));
        String target = SKIP.equals(action.getType()) ? ABSTAIN : action.getTargetPlayerId();
        votes.put(actor.getPlayerId(), target);
        data.put("votes", votes);
        Map<String, Object> notes = map(data.get("voteNotes"));
        notes.put(actor.getPlayerId(), text(action.getContent()).strip());
        data.put("voteNotes", notes);
        save(state, data);
        event(state, "VOTE_CAST", actor.getPlayerId(), ABSTAIN.equals(target) ? null : target, "你已提交本次投票。", Map.of("abstain", ABSTAIN.equals(target)), "PRIVATE", List.of(actor.getPlayerId()));
        if (eligibleVoters(state).stream().allMatch(p -> votes.containsKey(p.getPlayerId()))) resolveVoting(state, now);
    }

    private void resolveVoting(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        Map<String, Object> votes = map(data.get("votes"));
        Map<String, Object> notes = map(data.get("voteNotes"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GamePlayerState voter : eligibleVoters(state)) {
            String target = text(votes.getOrDefault(voter.getPlayerId(), ABSTAIN));
            votes.put(voter.getPlayerId(), target);
            if (!ABSTAIN.equals(target)) counts.merge(target, 1, Integer::sum);
            String message = voter.getDisplayName() + (ABSTAIN.equals(target) ? "选择弃票" : "投给了" + player(state, target).getDisplayName());
            String note = text(notes.get(voter.getPlayerId()));
            if (!note.isBlank()) message += "：" + note;
            event(state, "VOTE_REVEALED", voter.getPlayerId(), ABSTAIN.equals(target) ? null : target, message, Map.of("abstain", ABSTAIN.equals(target)));
        }
        data.put("votes", votes);
        save(state, data);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> leaders = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        if (leaders.size() != 1) {
            event(state, "VOTE_TIED", null, null, "最高票并列或全员弃票，本轮无人出局。", Map.of());
            startNight(state, now, true);
            return;
        }
        GamePlayerState eliminated = player(state, leaders.get(0));
        if (IDIOT.equals(eliminated.getRole()) && !strings(data.get("revealedIdiots")).contains(eliminated.getPlayerId())) {
            List<String> revealed = new ArrayList<>(strings(data.get("revealedIdiots")));
            revealed.add(eliminated.getPlayerId());
            data.put("revealedIdiots", revealed);
            save(state, data);
            event(state, "IDIOT_REVEALED", eliminated.getPlayerId(), eliminated.getPlayerId(),
                    eliminated.getDisplayName() + "亮出白痴身份免于放逐，之后不再拥有投票权。", Map.of("role", IDIOT));
            startNight(state, now, true);
            return;
        }
        beginDeaths(state, Map.of(eliminated.getPlayerId(), "EXILE"), false, "NIGHT", now);
    }

    private void beginDeaths(GameState state, Map<String, String> deaths, boolean night, String after, LocalDateTime now) {
        Map<String, Object> data = data(state);
        data.put("deaths", new ArrayList<>());
        data.put("deathActionsDone", new ArrayList<>());
        data.put("lastWordsDone", new ArrayList<>());
        data.put("afterDeaths", after);
        data.put("deathActor", "");
        data.put("lastWordsActor", "");
        save(state, data);
        deaths.entrySet().stream().sorted(Comparator.comparingInt(e -> player(state, e.getKey()).getSeatNumber()))
                .forEach(e -> recordDeath(state, e.getKey(), e.getValue(), night));
        continueDeaths(state, now);
    }

    private void recordDeath(GameState state, String id, String cause, boolean night) {
        GamePlayerState victim = player(state, id);
        if (!victim.isAlive()) return;
        victim.setAlive(false);
        Map<String, Object> data = data(state);
        List<Map<String, Object>> deaths = maps(data.get("deaths"));
        deaths.add(Map.of("playerId", id, "cause", cause, "night", night, "round", state.getRoundNumber()));
        data.put("deaths", deaths);
        save(state, data);
        String message = victim.getDisplayName() + ("EXILE".equals(cause) ? "被放逐。" : "死亡。");
        event(state, "DEATH", null, id, message, Map.of());
    }

    private void continueDeaths(GameState state, LocalDateTime now) {
        Map<String, Object> data = data(state);
        List<Map<String, Object>> deaths = maps(data.get("deaths"));
        Map<String, Object> hunter = deaths.stream().filter(d -> {
            String id = text(d.get("playerId"));
            return HUNTER.equals(player(state, id).getRole()) && Set.of("WOLF_KILL", "EXILE").contains(text(d.get("cause")))
                    && !strings(data.get("hunterUsed")).contains(id) && !strings(data.get("deathActionsDone")).contains(id);
        }).findFirst().orElse(null);
        if (hunter != null) {
            data.put("deathActor", text(hunter.get("playerId")));
            save(state, data);
            phase(state, "DEATH_ACTION", null, 30, now);
            return;
        }
        data.put("deathActor", "");
        Map<String, Object> words = deaths.stream().filter(d -> lastWordsAllowed(state, d)
                && !strings(data.get("lastWordsDone")).contains(text(d.get("playerId")))).findFirst().orElse(null);
        if (words != null) {
            String id = text(words.get("playerId"));
            data.put("lastWordsActor", id);
            save(state, data);
            phase(state, "LAST_WORDS", player(state, id).getSeatNumber(), 30, now);
            event(state, "LAST_WORDS_STARTED", id, null, player(state, id).getDisplayName() + "可以留下遗言。", Map.of());
            return;
        }
        data.put("lastWordsActor", "");
        save(state, data);
        if (finishIfWon(state)) return;
        if ("DAY_DISCUSS".equals(data.get("afterDeaths"))) startDiscussion(state, now);
        else startNight(state, now, true);
    }

    private void applyHunter(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        Map<String, Object> data = data(state);
        List<String> used = new ArrayList<>(strings(data.get("hunterUsed")));
        used.add(actor.getPlayerId());
        data.put("hunterUsed", used);
        List<String> done = new ArrayList<>(strings(data.get("deathActionsDone")));
        done.add(actor.getPlayerId());
        data.put("deathActionsDone", done);
        data.put("deathActor", "");
        save(state, data);
        if ("HUNTER_SHOOT".equals(action.getType())) {
            String target = action.getTargetPlayerId();
            String note = text(action.getContent()).strip();
            event(state, "HUNTER_SHOT", actor.getPlayerId(), target,
                    actor.getDisplayName() + "向" + player(state, target).getDisplayName() + "开枪" + (note.isBlank() ? "。" : "：" + note), Map.of());
            recordDeath(state, target, "HUNTER_SHOT", false);
        } else {
            event(state, "HUNTER_SKIPPED", actor.getPlayerId(), null, "你放弃了开枪。", Map.of(), "PRIVATE", List.of(actor.getPlayerId()));
        }
        continueDeaths(state, now);
    }

    private void applyLastWords(GameState state, GamePlayerState actor, PlayerAction action, LocalDateTime now) {
        if (!SKIP.equals(action.getType())) event(state, "LAST_WORDS", actor.getPlayerId(), null, actor.getDisplayName() + "的遗言：" + requireContent(action, 120), Map.of("statement", "PLAYER_CLAIM", "content", requireContent(action, 120)));
        Map<String, Object> data = data(state);
        List<String> done = new ArrayList<>(strings(data.get("lastWordsDone")));
        done.add(actor.getPlayerId());
        data.put("lastWordsDone", done);
        data.put("lastWordsActor", "");
        save(state, data);
        continueDeaths(state, now);
    }

    private boolean finishIfWon(GameState state) {
        List<GamePlayerState> survivors = alive(state);
        boolean wolfAlive = survivors.stream().anyMatch(p -> WOLF.equals(p.getRole()));
        String winner = "";
        if (!wolfAlive) winner = "GOOD";
        else if ("city".equals(map(state.getData().get("rules")).get("winCondition"))) {
            if (survivors.stream().allMatch(p -> WOLF.equals(p.getRole()))) winner = WOLF;
        } else {
            boolean hadVillagers = state.getPlayers().stream().anyMatch(p -> VILLAGER.equals(p.getRole()));
            boolean hadGods = state.getPlayers().stream().anyMatch(p -> !Set.of(WOLF, VILLAGER).contains(p.getRole()));
            boolean noVillagers = survivors.stream().noneMatch(p -> VILLAGER.equals(p.getRole()));
            boolean noGods = survivors.stream().noneMatch(p -> !Set.of(WOLF, VILLAGER).contains(p.getRole()));
            if ((hadVillagers && noVillagers) || (hadGods && noGods)) winner = WOLF;
        }
        if (winner.isBlank()) return false;
        String faction = winner;
        Set<String> winners = state.getPlayers().stream().filter(p -> WOLF.equals(faction) == WOLF.equals(p.getRole()))
                .map(GamePlayerState::getPlayerId).collect(Collectors.toCollection(LinkedHashSet::new));
        finish(state, winner, winners);
        return true;
    }

    private boolean lastWordsAllowed(GameState state, Map<String, Object> death) {
        String rule = text(map(state.getData().get("rules")).get("hasLastWords"));
        return "always".equals(rule) || ("first_night".equals(rule) && flag(death.get("night")) && number(death.get("round"), 0) == 1);
    }

    private boolean canSelfSave(GameState state, String actorId, String knife) {
        if (!actorId.equals(knife)) return true;
        String rule = text(map(state.getData().get("rules")).get("witchRule"));
        return "always_save".equals(rule) || ("first_night".equals(rule) && state.getRoundNumber() == 1);
    }

    private String wolfConsensus(GameState state, Map<String, Object> votes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        votes.values().stream().map(String::valueOf).filter(v -> !ABSTAIN.equals(v)).forEach(v -> counts.merge(v, 1, Integer::sum));
        if (counts.isEmpty()) return "";
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> targets = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey)
                .sorted(Comparator.comparingInt(id -> player(state, id).getSeatNumber())).toList();
        return targets.get(new Random(seed(state) ^ (state.getRoundNumber() * 0x9E3779B97F4A7C15L)).nextInt(targets.size()));
    }

    private List<GamePlayerState> eligibleVoters(GameState state) {
        List<String> revealed = strings(data(state).get("revealedIdiots"));
        return alive(state).stream().filter(p -> !revealed.contains(p.getPlayerId())).toList();
    }

    private List<String> aliveIds(GameState state, String except) {
        return alive(state).stream().filter(p -> !Objects.equals(p.getPlayerId(), except)).map(GamePlayerState::getPlayerId).toList();
    }

    private List<String> wolfIds(GameState state) {
        return state.getPlayers().stream().filter(p -> WOLF.equals(p.getRole())).map(GamePlayerState::getPlayerId).toList();
    }

    private GamePlayerState currentSpeaker(GameState state) {
        return state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(), state.getCurrentSeat())).findFirst().orElse(null);
    }

    private String turnKind(GameState state, Map<String, Object> data) {
        return switch (state.getPhase()) {
            case "NIGHT" -> "NIGHT_ACTION";
            case "DAY_DISCUSS", "LAST_WORDS" -> "SPEAK";
            case "DAY_INTERACTION" -> text(data.get("questionTarget")).isBlank() ? "ASK_PLAYER" : "ANSWER_PLAYER";
            case "DAY_VOTE" -> "VOTE";
            case "DEATH_ACTION" -> "HUNTER_SHOOT";
            default -> state.getPhase();
        };
    }

    private LegalAction matching(List<LegalAction> capabilities, PlayerAction action) {
        return capabilities.stream().filter(a -> a.type().equals(action.getType())
                && (!"NIGHT_ACTION".equals(a.type()) || Objects.equals(a.nightAction(), action.getNightAction()))).findFirst().orElse(null);
    }

    private PlayerAction normalize(PlayerAction source) {
        PlayerAction result = action(text(source.getType()).toUpperCase(Locale.ROOT), source.getContent(), source.getTargetPlayerId());
        result.setNightAction(text(source.getNightAction()).toUpperCase(Locale.ROOT));
        result.setUseHeal(source.isUseHeal());
        result.setAbstain(source.isAbstain());
        result.setExtra(source.getExtra());
        if ("VOTE".equals(result.getType()) && result.isAbstain()) result.setType(SKIP);
        if ("NIGHT_ACTION".equals(result.getType()) && (SKIP.equals(result.getNightAction()) || ("WITCH_SAVE".equals(result.getNightAction()) && !result.isUseHeal()))) result.setType(SKIP);
        return result;
    }

    private PlayerAction nightAction(String kind, String target, String content, boolean heal) {
        PlayerAction result = action("NIGHT_ACTION", content, target);
        result.setNightAction(kind);
        result.setUseHeal(heal);
        return result;
    }

    private Map<String, Object> normalizedRules(Room room) {
        Map<String, Object> rules = new LinkedHashMap<>();
        int count = number(room.getConfig().get("playerCount"), room.getMaxPlayers());
        String template = text(room.getConfig().getOrDefault("template", "standard"));
        rules.put("template", template);
        rules.put("playerCount", count);
        rules.put("witchRule", room.getConfig().getOrDefault("witchRule", "first_night"));
        rules.put("winCondition", room.getConfig().getOrDefault("winCondition", "side"));
        rules.put("speechTime", number(room.getConfig().get("speechTime"), 120));
        rules.put("hasLastWords", room.getConfig().getOrDefault("hasLastWords", "first_night"));
        rules.put("deathReveal", "settlement");
        rules.put("roleCounts", roleCounts(template, count));
        return rules;
    }

    private boolean validOption(Room room, String key, String fallback, Set<String> options) {
        return options.contains(text(room.getConfig().getOrDefault(key, fallback)));
    }

    private Map<String, Object> data(GameState state) {
        return map(state.getData().get("werewolf"));
    }

    private void save(GameState state, Map<String, Object> data) {
        state.getData().put("werewolf", data);
    }

    private long seed(GameState state) {
        Object value = state.getData().get("randomSeed");
        return value instanceof Number n ? n.longValue() : text(value).hashCode();
    }

    private int speechSeconds(GameState state) {
        return number(map(state.getData().get("rules")).get("speechTime"), 120);
    }

    private LocalDateTime interactionEnd(Map<String, Object> data) {
        return LocalDateTime.parse(text(data.get("interactionEndsAt")));
    }

    private int remainingSeconds(LocalDateTime now, LocalDateTime end) {
        return Math.max(1, (int) java.time.Duration.between(now, end).toSeconds());
    }

    private String lastEventId(GameState state) {
        List<Map<String, Object>> events = maps(state.getData().get("events"));
        return events.isEmpty() ? "" : text(events.get(events.size() - 1).get("eventId"));
    }

    private String choose(VisibleObservation observation, List<String> targets) {
        return targets.get(Math.floorMod(Objects.hash(observation.instanceId(), observation.actorId(), observation.round(), observation.turnKind()), targets.size()));
    }

    private String lastSpeakerName(VisibleObservation observation) {
        String last = "";
        for (Map<String, Object> e : observation.events()) {
            if (!"SPEECH".equals(text(e.get("type")))) continue;
            String id = text(e.get("actorId"));
            if (id.isBlank() || id.equals(observation.actorId())) continue;
            last = observation.players().stream().filter(p -> id.equals(text(p.get("playerId")))).map(p -> text(p.get("displayName"))).findFirst().orElse("");
        }
        return last;
    }

    private static String roleName(String role) {
        return switch (role) {
            case WOLF -> "狼人";
            case SEER -> "预言家";
            case WITCH -> "女巫";
            case GUARD -> "守卫";
            case HUNTER -> "猎人";
            case IDIOT -> "白痴";
            default -> "村民";
        };
    }

    private static Map<String, Object> loadKnowledge() {
        try (InputStream stream = WerewolfRuleSet.class.getResourceAsStream("/game-knowledge/werewolf.json")) {
            if (stream == null) throw new IllegalStateException("狼人杀知识包缺失");
            return new ObjectMapper().readValue(stream, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new IllegalStateException("狼人杀知识包格式错误", exception);
        }
    }
}
