package com.aisocialgame.engine.v2.undercover;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.ValidationResult;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.aisocialgame.service.ai.v2.AiConversationContext;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Version two wordplay rules. All state changes are local; AI jobs are separate capabilities. */
@Component
public final class UndercoverRuleSet implements GameRuleSet {
    private static final Set<String> SPEAK_TIMES = Set.of("0", "30", "60", "90");
    private static final Set<String> VOTING_PHASES = Set.of("VOTING", "RUNOFF");
    private final UndercoverWordCatalog catalog;

    public UndercoverRuleSet(UndercoverWordCatalog catalog) { this.catalog = catalog; }

    @Override public com.aisocialgame.model.Game definition() {
        var game=UndercoverDefinition.metadata();
        game.setEngineBacked(true); game.setRuleVersion(2);
        game.setPhaseDefinitions(UndercoverDefinition.phaseDefinitions()); game.setRoleDefinitions(UndercoverDefinition.roleDefinitions());
        var fields=new java.util.ArrayList<>(game.getConfigSchema()); fields.add(new com.aisocialgame.model.GameConfigOption("spyCount","手动卧底数量","number",1,null,1,3)); game.setConfigSchema(fields);
        return com.aisocialgame.engine.v2.GameConfiguration.withDifficulty(game);
    }
    @Override public java.util.Map<String,Object> confirmedFacts(java.util.Map<String,Object> e) {
        String type=text(e.get("type")); var d=map(e.get("data")); java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
        switch (type) {
                case "VOTE_TARGET" -> com.aisocialgame.service.ai.v2.AiInformationSources.ballot(e, d, result);
                case "VOTE_CAST" -> result.put("submittedBy", text(e.get("actorId")));
                case "VOTE_REVEAL" -> com.aisocialgame.service.ai.v2.AiInformationSources.copy(d, result, "votes", "tally");
                case "ELIMINATED" -> { result.put("eliminatedPlayerId", text(e.get("actorId"))); com.aisocialgame.service.ai.v2.AiInformationSources.copy(d, result, "role"); }
                case "WORD_ASSIGNED" -> com.aisocialgame.service.ai.v2.AiInformationSources.copy(d, result, "word", "blank");
                default -> { }
            }
        return result;
    }
    @Override public java.util.Map<String,Object> privateConfirmed(java.util.Map<String,Object> facts) {
        java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
        com.aisocialgame.service.ai.v2.AiInformationSources.copy(facts,result,"word", "blank", "wordKnowledge", "myVote");
        return result;
    }
    @Override public String gameId() { return "undercover"; }

    @Override public ValidationResult validateStart(Room room) {
        int count = room.getSeats().size();
        if (count < 4 || count > 10) return ValidationResult.invalid("谁是卧底需要4-10名参赛玩家");
        Map<String, Object> config = room.getConfig();
        String mode = text(config.getOrDefault("spyMode", "auto"));
        if (!Set.of("auto", "manual").contains(mode)) return ValidationResult.invalid("卧底数量模式不支持");
        if ("manual".equals(mode)) {
            String raw = text(config.get("spyCount"));
            int spies = number(raw, -1);
            if (!raw.matches("[0-9]+") || spies < 1 || spies > (count - 1) / 3) {
                return ValidationResult.invalid("手动卧底数量必须是1到" + ((count - 1) / 3) + "之间的整数");
            }
        }
        if (!SPEAK_TIMES.contains(text(config.getOrDefault("speakTime", 60)))) return ValidationResult.invalid("发言时长仅支持0、30、60或90秒");
        String pack = text(config.getOrDefault("wordPack", "daily"));
        if (!UndercoverWordCatalog.CATEGORIES.contains(pack) && !"custom".equals(pack)) return ValidationResult.invalid("词库类型不支持");
        if ("custom".equals(pack)) {
            if (room.getHostUserId() == null || room.getSeats().stream().anyMatch(s -> room.getHostUserId().equals(s.getPlayerId()))) {
                return ValidationResult.invalid("自定义词库的出题主持不能参加本局游戏");
            }
            try { customPairs(room); }
            catch (IllegalArgumentException e) { return ValidationResult.invalid(e.getMessage()); }
        }
        return ValidationResult.ok();
    }

    @Override public GameState initialize(Room room, LocalDateTime now) {
        ValidationResult validation = validateStart(room);
        require(validation.valid(), validation.message());
        GameState state = newState(room, now);
        state.getData().put("knowledgeVersion", "custom".equals(room.getConfig().get("wordPack")) ? "undercover-custom-v1" : "undercover-words-v1");
        Random random = new Random(((Number) state.getData().get("randomSeed")).longValue());
        String pack = text(room.getConfig().getOrDefault("wordPack", "daily"));
        List<UndercoverWordCatalog.WordPair> pool = "custom".equals(pack) ? customPairs(room) : catalog.category(pack);
        Set<String> used = new LinkedHashSet<>(strings(room.getConfig().get("usedWordPairIds")));
        List<UndercoverWordCatalog.WordPair> available = pool.stream().filter(pair -> !used.contains(pair.id())).toList();
        if (available.isEmpty()) {
            used.removeAll(pool.stream().map(UndercoverWordCatalog.WordPair::id).toList());
            available = pool;
        }
        UndercoverWordCatalog.WordPair pair = available.get(random.nextInt(available.size()));
        used.add(pair.id());
        Map<String, Object> roomConfig = new LinkedHashMap<>(room.getConfig());
        roomConfig.put("usedWordPairIds", new ArrayList<>(used)); room.setConfig(roomConfig);
        // The side order is never a faction assignment; either word can be the majority word.
        boolean aIsMajority = random.nextBoolean();
        UndercoverWordCatalog.WordKnowledge majority = aIsMajority ? pair.sideA() : pair.sideB();
        UndercoverWordCatalog.WordKnowledge minority = aIsMajority ? pair.sideB() : pair.sideA();
        state.getData().put("civilianWord", majority.word());
        state.getData().put("undercoverWord", minority.word());
        state.getData().put("wordPairId", pair.id());
        state.getData().put("hasBlank", flag(room.getConfig().get("hasBlank")));
        state.getData().put("noEliminationRounds", 0);
        state.getData().put("wordPack", pack);
        List<GamePlayerState> shuffled = new ArrayList<>(state.getPlayers()); Collections.shuffle(shuffled, random);
        int spyCount = "manual".equals(text(room.getConfig().get("spyMode")))
                ? number(room.getConfig().get("spyCount"), 1) : (shuffled.size() <= 6 ? 1 : 2);
        Map<String, Object> knowledgeByPlayer = new LinkedHashMap<>();
        for (int i = 0; i < shuffled.size(); i++) {
            GamePlayerState p = shuffled.get(i);
            boolean blank = flag(room.getConfig().get("hasBlank")) && i == shuffled.size() - 1;
            p.setRole(blank ? "BLANK" : i < spyCount ? "UNDERCOVER" : "CIVILIAN");
            p.setWord(blank ? "" : i < spyCount ? minority.word() : majority.word());
            p.setAlive(true);
            if (!blank) knowledgeByPlayer.put(p.getPlayerId(), (i < spyCount ? minority : majority).visibleData());
            event(state, "WORD_ASSIGNED", p.getPlayerId(), null, "", Map.of("word", p.getWord(), "blank", blank), "PRIVATE", List.of(p.getPlayerId()));
            event(state, "ROLE_ASSIGNED", p.getPlayerId(), null, "", Map.of("word", p.getWord(), "role", p.getRole()), "GOD", List.of());
        }
        state.getData().put("wordKnowledgeByPlayer", knowledgeByPlayer);
        List<GamePlayerState> alive = alive(state);
        int firstSeat = alive.get(random.nextInt(alive.size())).getSeatNumber();
        state.getData().put("questionCursorSeat", firstSeat);
        beginDescription(state, firstSeat, now);
        event(state, "GAME_START", null, null, "词语已发放：有词玩家只知道词语；白板存活到最后两人独赢。", Map.of("ruleVersion", 2));
        return state;
    }

    @Override public void apply(GameState state, String actorId, PlayerAction action, LocalDateTime now) {
        require(!"SETTLEMENT".equals(state.getPhase()), "本局已经结束");
        GamePlayerState actor = player(state, actorId);
        require(actor.isAlive(), "你已出局，无法操作");
        require(action != null && action.getType() != null, "动作不能为空");
        require(!expired(state, now), "当前操作已超时，请等待下一阶段", "PHASE_CHANGED");
        String type = action.getType().toUpperCase(Locale.ROOT);
        // Legal-action projection removes completed ballots, so classify before its generic rejection.
        if (VOTING_PHASES.contains(state.getPhase())) {
            require(!votes(state).containsKey(actorId), "已完成投票", "ALREADY_ACTED");
        } else {
            require(Objects.equals(state.getCurrentSeat(), actor.getSeatNumber()), "当前不需要你发言", "NOT_YOUR_TURN");
        }
        LegalAction capability = legalActions(state, actorId).stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow(() -> bad("当前不能执行这个动作"));
        if (VOTING_PHASES.contains(state.getPhase())) {
            boolean abstain = "SKIP".equals(type) || action.isAbstain();
            if (!abstain) require(capability.targets().contains(action.getTargetPlayerId()), "只能投给其他合法候选玩家");
            submitVote(state, actor, abstain ? "abstain" : action.getTargetPlayerId());
            if (votes(state).size() == alive(state).size()) resolveVote(state, now);
            return;
        }
        if ("SKIP".equals(type)) {
            event(state, "SKIP", actorId, null, actor.getDisplayName() + "选择跳过。", Map.of());
            completeCurrentSpeech(state, now);
            return;
        }
        String content = requireContent(action, capability.maxLength());
        // Exact and obfuscated answer names are rejected for everyone, including the blank.
        require(!revealsAnswer(state, content), "描述中不能直接包含本局词语或别名");
        Map<String, Object> data = new LinkedHashMap<>(); data.put("content", content);
        if ("ASK_PLAYER".equals(type)) {
            require(capability.targets().contains(action.getTargetPlayerId()), "请选择本轮尚未被质疑的其他存活玩家");
            event(state, "ASK_PLAYER", actorId, action.getTargetPlayerId(), actor.getDisplayName() + "问：" + content, data);
            List<Map<String, Object>> events = maps(state.getData().get("events"));
            Map<String, Object> question = new LinkedHashMap<>(Map.of("actorId", actorId, "targetId", action.getTargetPlayerId(), "content", content,
                    "eventId", text(events.get(events.size() - 1).get("eventId"))));
            state.getData().put("activeQuestion", question);
            List<String> challenged = new ArrayList<>(strings(state.getData().get("challengedPlayers"))); challenged.add(action.getTargetPlayerId());
            state.getData().put("challengedPlayers", challenged);
            phase(state, "RESPONSE", player(state, action.getTargetPlayerId()).getSeatNumber(), 20, now);
        } else {
            String target = "ANSWER_PLAYER".equals(type) ? text(map(state.getData().get("activeQuestion")).get("actorId")) : null;
            if ("ANSWER_PLAYER".equals(type)) data.put("questionEventId", text(map(state.getData().get("activeQuestion")).get("eventId")));
            event(state, type, actorId, target, actor.getDisplayName() + "：" + content, data);
            completeCurrentSpeech(state, now);
        }
    }

    @Override public void advance(GameState state, LocalDateTime now) {
        if ("SETTLEMENT".equals(state.getPhase()) || !expired(state, now)) return;
        if (VOTING_PHASES.contains(state.getPhase())) {
            for (GamePlayerState actor : alive(state)) if (!votes(state).containsKey(actor.getPlayerId())) submitVote(state, actor, "abstain");
            resolveVote(state, now);
            return;
        }
        current(state).ifPresent(actor -> event(state, "TIMEOUT", actor.getPlayerId(), null, actor.getDisplayName() + "本次发言超时，自动跳过。", Map.of()));
        completeCurrentSpeech(state, now);
    }

    @Override public void onAiFailure(GameState state, TurnRequest turn, LocalDateTime now) {
        if (!isTurnCurrent(state, turn)) return;
        if ("DESCRIPTION".equals(state.getPhase())) {
            GamePlayerState actor = player(state, turn.actorId());
            event(state, "TIMEOUT", actor.getPlayerId(), null,
                    actor.getDisplayName() + "本次未能完成描述，跳过这一发言机会。", Map.of());
            completeCurrentSpeech(state, now);
            return;
        }
        // In a simultaneous ballot, the shared default submits only this AI's
        // legal abstention; it must not advance the deadline for other voters.
        GameRuleSet.super.onAiFailure(state, turn, now);
    }

    @Override public List<LegalAction> legalActions(GameState state, String actorId) {
        GamePlayerState actor = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).findFirst().orElse(null);
        if (actor == null || !actor.isAlive() || "SETTLEMENT".equals(state.getPhase())) return List.of();
        List<String> others = alive(state).stream().filter(p -> !p.getPlayerId().equals(actorId)).map(GamePlayerState::getPlayerId).toList();
        if (VOTING_PHASES.contains(state.getPhase())) {
            if (votes(state).containsKey(actorId)) return List.of();
            List<String> targets = "RUNOFF".equals(state.getPhase()) ? others.stream().filter(strings(state.getData().get("runoffCandidates"))::contains).toList() : others;
            return List.of(LegalAction.target("VOTE", "提交投票", targets, 0), LegalAction.simple("SKIP", "弃票"));
        }
        if (!Objects.equals(state.getCurrentSeat(), actor.getSeatNumber())) return List.of();
        return switch (state.getPhase()) {
            case "DESCRIPTION" -> List.of(LegalAction.text("SPEAK", "描述你的词语", 90));
            case "CHALLENGE" -> List.of(LegalAction.target("ASK_PLAYER", "提出一个质疑", others.stream().filter(id -> !strings(state.getData().get("challengedPlayers")).contains(id)).toList(), 60), LegalAction.simple("SKIP", "没有质疑，跳过"));
            case "RESPONSE" -> List.of(LegalAction.text("ANSWER_PLAYER", "回应质疑", 60), LegalAction.simple("SKIP", "跳过回应"));
            case "TIE_DEFENSE" -> List.of(LegalAction.text("SPEAK", "发表一次辩解", 60), LegalAction.simple("SKIP", "跳过辩解"));
            default -> List.of();
        };
    }

    @Override public List<TurnRequest> pendingTurns(GameState state) {
        if ("SETTLEMENT".equals(state.getPhase())) return List.of();
        if (VOTING_PHASES.contains(state.getPhase())) return alive(state).stream().filter(RuleSupport::automated)
                .filter(p -> !votes(state).containsKey(p.getPlayerId())).map(p -> turn(state, p.getPlayerId(), "VOTE")).toList();
        return current(state).filter(RuleSupport::automated).filter(p -> !legalActions(state, p.getPlayerId()).isEmpty())
                .map(p -> List.of(turn(state, p.getPlayerId(), "CHALLENGE".equals(state.getPhase()) ? "ASK_PLAYER" : "RESPONSE".equals(state.getPhase()) ? "ANSWER_PLAYER" : "SPEAK")))
                .orElse(List.of());
    }

    @Override public Map<String, Object> publicData(GameState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleVersion", 2); data.put("wordPack", text(state.getData().get("wordPack")));
        data.put("phaseLabel", switch (state.getPhase()) { case "CHALLENGE" -> "质疑"; case "RESPONSE" -> "回应"; case "TIE_DEFENSE" -> "平票辩解"; case "RUNOFF" -> "复投"; case "DESCRIPTION" -> "描述"; case "VOTING" -> "投票"; default -> "结算"; });
        data.put("descriptionOrder", strings(state.getData().get("descriptionOrder")));
        data.put("questioners", strings(state.getData().get("questioners")));
        data.put("challengedPlayers", strings(state.getData().get("challengedPlayers")));
        data.put("runoffCandidates", strings(state.getData().get("runoffCandidates")));
        data.put("activeQuestion", map(state.getData().get("activeQuestion")));
        data.put("votedPlayers", new ArrayList<>(votes(state).keySet()));
        data.put("lastVoteResult", map(state.getData().get("lastVoteResult")));
        data.put("noEliminationRounds", number(state.getData().get("noEliminationRounds"), 0));
        data.put("hasBlank", flag(state.getData().get("hasBlank")));
        if ("SETTLEMENT".equals(state.getPhase())) {
            data.put("civilianWord", text(state.getData().get("civilianWord")));
            data.put("undercoverWord", text(state.getData().get("undercoverWord")));
            data.put("winner", text(state.getData().get("winner")));
        }
        return data;
    }

    @Override public Map<String, Object> privateData(GameState state, String actorId) {
        Optional<GamePlayerState> found = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).findFirst();
        if (found.isEmpty()) return Map.of();
        GamePlayerState actor = found.get();
        Map<String, Object> data = new LinkedHashMap<>();
        boolean blank = "BLANK".equals(actor.getRole());
        data.put("blank", blank); data.put("word", text(actor.getWord()));
        if (!blank) data.put("wordKnowledge", map(map(state.getData().get("wordKnowledgeByPlayer")).get(actorId)));
        if (votes(state).containsKey(actorId)) data.put("myVote", votes(state).get(actorId));
        return data;
    }

    @Override public String visibleRole(GameState state, GamePlayerState player, String viewerId) {
        if ("SETTLEMENT".equals(state.getPhase()) || !player.isAlive()) return player.getRole();
        return player.getPlayerId().equals(viewerId) && "BLANK".equals(player.getRole()) ? "BLANK" : null;
    }

    @Override public AiConversationContext conversation(VisibleObservation o) {
        return AiConversationContext.create(o.phase(), o, a -> switch (a.type()) {
            case "VOTE" -> AiConversationContext.guide("不公开解释尚未揭晓的投票。", "VOTE");
            case "SKIP" -> VOTING_PHASES.contains(o.phase())
                    ? AiConversationContext.guide("此动作是弃票，不是让出发言。", "ABSTAIN")
                    : AiConversationContext.guide("本次不发言；不附带一段补充。", "YIELD");
            case "ASK_PLAYER" -> AiConversationContext.guide("通常只问一个具体问题。", "QUESTION");
            case "ANSWER_PLAYER" -> AiConversationContext.guide("通常一至两句处理被问的内容；必要时可在合法上限内展开。", "CLARIFY", "STATE_POSITION", "REVISE");
            case "SPEAK" -> "DESCRIPTION".equals(o.phase())
                    ? AiConversationContext.guide("通常15～40字，集中一个描述角度；必须完成本轮描述。", "DESCRIBE")
                    : AiConversationContext.guide("集中澄清关键误会，需要完整辩解时可在合法上限内展开。", "CLARIFY", "STATE_POSITION", "REVISE");
            default -> AiConversationContext.guide("按合法行动参与。", "CHOOSE_FOR_SITUATION");
        }, Set.of("CHALLENGE"), Set.of("ANSWER_PLAYER", "SKIP", "TIMEOUT"));
    }

    @Override public String instruction(VisibleObservation observation) {
        String phaseInstruction = switch (observation.phase()) {
            case "DESCRIPTION" -> "给出一条新的有效描述，一般15-40字。首轮可以说场景或感受，后续换一个尚未用过的属性；可以简短接住刚发生的误解。";
            case "CHALLENGE" -> "你只有一次质疑机会。找一个真实公开语义冲突或跟风迹象，点名问一个具体问题；没有实质疑点就SKIP，不为制造戏剧强行找茬。";
            case "RESPONSE" -> "你正在回应别人刚刚对你的质疑。先处理被问的那一点，必要时补充直接相关的依据，不强制另加线索；最多60字，不另起无限追问。";
            case "TIE_DEFENSE" -> "你处于最高票并列的一次辩解。用公开事实澄清最关键的误会，可以克制地紧张，最多60字；无法补充时可以SKIP。";
            case "VOTING", "RUNOFF" -> "根据公开语义和此前表态选择合法候选人。使用evidenceEventIds记录依据；证据不足可以弃票，不能仅凭发言字数判人。投票阶段speech留空，防止尚未揭晓时泄露选择。";
            default -> "只执行当前合法动作。";
        };
        return "你是认真参加谁是卧底的桌游朋友。你没有被告知自己属于平民还是卧底，只知道自己的词；" +
                "不要从人设、词库排列或经验中猜定真实阵营。通过词义、场景、用法的异同更新自己的多个假设。" +
                "白板没有词，可以根据公开发言猜测，也可以首轮试探或伪装；不强制使用暴露身份的固定不确定措辞，但不能伪造系统裁决或已发生的公共记录。存活到最后两人才能独赢，三人时尚未获胜。" +
                "不能直接说出本局词语、别名、拼音、拆字或字数提示，也不要念出知识库定义。" +
                "把description维度写入usedDescriptions，把有依据的怀疑写入beliefs与evidenceEventIds；" +
                "改变立场要回应新证据，误投可以简短认错，不能把上一局的敌友关系带来。" +
                "情绪由刚发生的互动引起并随局势恢复；语言和轻量神态要一致，允许安静和停顿，不必每句都有动作。" +
                "证据与胜负比热闹更重要，不要讨好、辱骂、说教或假装知道不可见信息。" + phaseInstruction;
    }

    @Override public List<String> knowledge(VisibleObservation observation) {
        if (flag(observation.privateFacts().get("blank"))) return List.of("白板只根据公开描述建立可修正的多个词义假设，不能知道任一词语资料。");
        Map<String, Object> own = map(observation.privateFacts().get("wordKnowledge"));
        if (own.isEmpty()) return List.of();
        return List.of("仅你自己的词义资料（禁止原样公开）：" + text(own.get("definition")),
                "独立属性：" + String.join("；", strings(own.get("attributes"))),
                "可联想的生活场景：" + String.join("；", strings(own.get("scenes"))));
    }

    @Override public PlayerAction fallback(VisibleObservation observation) {
        boolean canSkip = observation.legalActions().stream().anyMatch(a -> "SKIP".equals(a.type()));
        if (canSkip && !"RESPONSE".equals(observation.phase())) return action("SKIP", "", null);
        Map<String, Object> own = map(observation.privateFacts().get("wordKnowledge"));
        List<String> cues = new ArrayList<>(strings(own.get("attributes"))); cues.addAll(strings(own.get("scenes")));
        Set<String> used = new LinkedHashSet<>(strings(observation.memory().get("usedDescriptions")));
        int variation = Math.floorMod(Objects.hash(observation.actorId(), observation.round()), 4);
        String[] prefixes = {"我想到的一个特点是", "换个角度说，", "对我来说，", "我补一个细节："};
        String content = "目前没有足够线索，我还不能确定范围，先保留判断。";
        if (!cues.isEmpty()) {
            List<String> previous = new ArrayList<>(used);
            observation.events().stream().filter(e -> observation.actorId().equals(e.get("actorId")))
                    .map(com.aisocialgame.service.ai.v2.AiGrounding::content).forEach(previous::add);
            maps(observation.memory().get("recentDecisions")).forEach(d -> previous.add(text(d.get("speech"))));
            int max = "RESPONSE".equals(observation.phase()) ? 60 : 90;
            String cue = cues.stream().filter(c -> previous.stream().noneMatch(p -> p.contains(c)))
                    .filter(c -> !catalog.containsForbidden(c, text(observation.privateFacts().get("word"))))
                    .filter(c -> c.codePointCount(0, c.length()) <= max - 20).findFirst().orElse("");
            content = cue.isBlank() ? "我暂时想不到不重复的新线索，先保留判断。"
                    : ("RESPONSE".equals(observation.phase()) ? "关于这点，我能补充的是" : prefixes[variation]) + cue + "。";
        } else if (observation.events().stream().anyMatch(e -> "SPEAK".equals(e.get("type")))) {
            content = "前面的线索还不足以让我确定范围，我先保留判断。";
        }
        String word = text(observation.privateFacts().get("word"));
        if ("DESCRIPTION".equals(observation.phase()) && acknowledgedCivilianVote(observation))
            content = "刚才我投的人公开为平民了，我得重新核对判断。" + content;
        if (catalog.containsForbidden(content, word)) content = "我先从自己熟悉的场景来想，还不想把范围说死。";
        if (content.codePointCount(0, content.length()) > 90) content = "刚才我投的人公开为平民了，我得重新核对判断。";
        return action("RESPONSE".equals(observation.phase()) ? "ANSWER_PLAYER" : "SPEAK", content, null);
    }

    private boolean acknowledgedCivilianVote(VisibleObservation observation) {
        List<Map<String, Object>> events = observation.events();
        String target = "";
        for (Map<String, Object> e : events) {
            if (number(e.get("round"), -1) != observation.round() - 1) continue;
            if ("VOTE_REVEAL".equals(e.get("type"))) target = text(map(map(e.get("data")).get("votes")).get(observation.actorId()));
            if (!target.isBlank() && target.equals(e.get("actorId")) && "ELIMINATED".equals(e.get("type"))
                    && "CIVILIAN".equals(map(e.get("data")).get("role"))) return true;
        }
        return false;
    }

    @Override public List<String> validateDecision(VisibleObservation observation, AiTurnDecision decision) {
        List<String> errors = new ArrayList<>();
        String word = text(observation.privateFacts().getOrDefault("word", observation.self().get("word")));
        if (catalog.containsForbidden(decision.speech(), word)
                || catalog.containsForbidden(decision.action() == null ? "" : decision.action().getContent(), word)
                || catalog.containsForbidden(decision.presentation().toString(), word)) errors.add("SECRET_WORD_LEAK");
        if (VOTING_PHASES.contains(observation.phase()) && !decision.speech().isBlank()) errors.add("VOTE_MUST_REMAIN_PRIVATE");
        if (decision.action() != null && Set.of("SPEAK", "ASK_PLAYER", "ANSWER_PLAYER").contains(decision.action().getType()) && decision.speech().isBlank()) errors.add("EMPTY_GAME_SPEECH");
        return errors;
    }

    private void beginDescription(GameState state, int startSeat, LocalDateTime now) {
        List<String> order = orderedFrom(state, startSeat).stream().map(GamePlayerState::getPlayerId).toList();
        state.getData().put("roundStartSeat", player(state, order.getFirst()).getSeatNumber());
        state.getData().put("descriptionOrder", order); state.getData().put("descriptionIndex", 0);
        state.getData().put("speakers", new ArrayList<>()); state.getData().put("votes", new LinkedHashMap<>());
        state.getData().put("runoffCandidates", List.of()); state.getData().put("activeQuestion", Map.of());
        state.getData().put("questioners", List.of()); state.getData().put("challengedPlayers", new ArrayList<>());
        phase(state, "DESCRIPTION", player(state, order.getFirst()).getSeatNumber(), number(map(state.getData().get("rules")).get("speakTime"), 60), now);
        event(state, "ROUND_START", null, null, "进入第" + state.getRoundNumber() + "轮描述。", Map.of("descriptionOrder", order));
    }

    private void completeCurrentSpeech(GameState state, LocalDateTime now) {
        switch (state.getPhase()) {
            case "DESCRIPTION" -> {
                List<String> order = strings(state.getData().get("descriptionOrder"));
                int index = number(state.getData().get("descriptionIndex"), 0);
                List<String> speakers = new ArrayList<>(strings(state.getData().get("speakers"))); speakers.add(order.get(index));
                state.getData().put("speakers", speakers); state.getData().put("descriptionIndex", ++index);
                if (index < order.size()) phase(state, "DESCRIPTION", player(state, order.get(index)).getSeatNumber(), number(map(state.getData().get("rules")).get("speakTime"), 60), now);
                else beginChallenges(state, now);
            }
            case "CHALLENGE", "RESPONSE" -> nextChallenge(state, now);
            case "TIE_DEFENSE" -> {
                List<String> candidates = strings(state.getData().get("runoffCandidates"));
                int index = number(state.getData().get("defenseIndex"), 0) + 1; state.getData().put("defenseIndex", index);
                if (index < candidates.size()) phase(state, "TIE_DEFENSE", player(state, candidates.get(index)).getSeatNumber(), 20, now);
                else beginVote(state, true, now);
            }
            default -> throw bad("当前阶段无法推进发言");
        }
    }

    private void beginChallenges(GameState state, LocalDateTime now) {
        List<GamePlayerState> order = orderedFrom(state, number(state.getData().get("questionCursorSeat"), 0));
        List<String> questioners = order.stream().limit(2).map(GamePlayerState::getPlayerId).toList();
        state.getData().put("questioners", questioners); state.getData().put("challengeIndex", 0);
        state.getData().put("questionCursorSeat", order.get(2 % order.size()).getSeatNumber());
        phase(state, "CHALLENGE", player(state, questioners.getFirst()).getSeatNumber(), 20, now);
        event(state, "CHALLENGE_START", null, null, "本轮有两次质疑机会；可以选择跳过，每个被质疑者回应一次。", Map.of("questioners", questioners));
    }

    private void nextChallenge(GameState state, LocalDateTime now) {
        state.getData().put("activeQuestion", Map.of());
        List<String> questioners = strings(state.getData().get("questioners"));
        int index = number(state.getData().get("challengeIndex"), 0) + 1; state.getData().put("challengeIndex", index);
        if (index < questioners.size()) phase(state, "CHALLENGE", player(state, questioners.get(index)).getSeatNumber(), 20, now);
        else beginVote(state, false, now);
    }

    private void beginVote(GameState state, boolean runoff, LocalDateTime now) {
        state.getData().put("votes", new LinkedHashMap<>()); state.getData().put("activeQuestion", Map.of());
        phase(state, runoff ? "RUNOFF" : "VOTING", null, runoff ? 15 : 30, now);
        event(state, "VOTE_START", null, null, runoff ? "开始复投，只能投给平票候选人，也可以弃票。" : "开始投票，投齐或时间结束后统一公布。", Map.of("runoff", runoff));
    }

    private void submitVote(GameState state, GamePlayerState actor, String target) {
        Map<String, Object> votes = votes(state); require(!votes.containsKey(actor.getPlayerId()), "已完成投票", "ALREADY_ACTED");
        votes.put(actor.getPlayerId(), target); state.getData().put("votes", votes);
        event(state, "VOTE_CAST", actor.getPlayerId(), null, actor.getDisplayName() + "已提交投票。", Map.of());
        event(state, "VOTE_TARGET", actor.getPlayerId(), "abstain".equals(target) ? null : target, "", Map.of("abstain", "abstain".equals(target)), "PRIVATE", List.of(actor.getPlayerId()));
    }

    private void resolveVote(GameState state, LocalDateTime now) {
        Map<String, Object> ballots = votes(state);
        Map<String, Long> tally = ballots.values().stream().map(Object::toString).filter(v -> !"abstain".equals(v)).collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
        state.getData().put("lastVoteResult", Map.of("round", state.getRoundNumber(), "phase", state.getPhase(), "votes", new LinkedHashMap<>(ballots), "tally", tally));
        event(state, "VOTE_REVEAL", null, null, "本次投票已全部揭晓。", Map.of("votes", ballots, "tally", tally));
        if (tally.isEmpty()) { noElimination(state, now); return; }
        long most = tally.values().stream().max(Long::compareTo).orElse(0L);
        List<String> leaders = alive(state).stream().map(GamePlayerState::getPlayerId).filter(id -> tally.getOrDefault(id, 0L) == most).toList();
        if (leaders.size() > 1) {
            if ("RUNOFF".equals(state.getPhase())) { noElimination(state, now); return; }
            state.getData().put("runoffCandidates", leaders); state.getData().put("defenseIndex", 0);
            phase(state, "TIE_DEFENSE", player(state, leaders.getFirst()).getSeatNumber(), 20, now);
            event(state, "TIE", null, null, "最高票并列，候选人各有一次辩解机会。", Map.of("candidates", leaders));
            return;
        }
        GamePlayerState eliminated = player(state, leaders.getFirst()); eliminated.setAlive(false);
        state.getData().put("noEliminationRounds", 0);
        event(state, "ELIMINATED", eliminated.getPlayerId(), null, eliminated.getDisplayName() + "出局，身份为" + roleLabel(eliminated.getRole()) + "。", Map.of("role", eliminated.getRole()));
        if (!finishIfWon(state)) nextRound(state, now);
    }

    private void noElimination(GameState state, LocalDateTime now) {
        int count = number(state.getData().get("noEliminationRounds"), 0) + 1;
        state.getData().put("noEliminationRounds", count);
        event(state, "NO_ELIMINATION", null, null, "本轮无人出局。", Map.of("consecutiveRounds", count));
        if (count >= 3) finish(state, "DRAW", List.of()); else nextRound(state, now);
    }

    private void nextRound(GameState state, LocalDateTime now) {
        int previous = number(state.getData().get("roundStartSeat"), 0);
        List<GamePlayerState> survivors = alive(state);
        int next = survivors.stream().filter(p -> p.getSeatNumber() > previous).findFirst().orElse(survivors.getFirst()).getSeatNumber();
        state.setRoundNumber(state.getRoundNumber() + 1); beginDescription(state, next, now);
    }

    private boolean finishIfWon(GameState state) {
        List<GamePlayerState> survivors = alive(state);
        long spies = survivors.stream().filter(p -> "UNDERCOVER".equals(p.getRole())).count();
        Optional<GamePlayerState> blank = survivors.stream().filter(p -> "BLANK".equals(p.getRole())).findFirst();
        if (blank.isPresent() && survivors.size() == 2) { finish(state, "BLANK", List.of(blank.get().getPlayerId())); return true; }
        String winner = spies == 0 && blank.isEmpty() ? "CIVILIAN" : spies > 0 && spies >= survivors.size() - spies ? "UNDERCOVER" : null;
        if (winner == null) return false;
        finish(state, winner, state.getPlayers().stream().filter(p -> winner.equals(p.getRole())).map(GamePlayerState::getPlayerId).toList());
        return true;
    }

    private boolean revealsAnswer(GameState state, String content) {
        return catalog.containsForbidden(content, text(state.getData().get("civilianWord")))
                || catalog.containsForbidden(content, text(state.getData().get("undercoverWord")));
    }

    private Map<String, Object> votes(GameState state) { return map(state.getData().get("votes")); }
    private Optional<GamePlayerState> current(GameState state) { return alive(state).stream().filter(p -> Objects.equals(state.getCurrentSeat(), p.getSeatNumber())).findFirst(); }
    private List<GamePlayerState> orderedFrom(GameState state, int seat) {
        List<GamePlayerState> result = new ArrayList<>(alive(state));
        int index = 0; while (index < result.size() && result.get(index).getSeatNumber() < seat) index++;
        Collections.rotate(result, -(index % result.size())); return result;
    }
    private static String roleLabel(String role) { return "BLANK".equals(role) ? "白板" : "UNDERCOVER".equals(role) ? "卧底" : "平民"; }

    private List<UndercoverWordCatalog.WordPair> customPairs(Room room) {
        List<Map<String, Object>> entries = maps(room.getPrivateConfig().get("customWords"));
        if (entries.isEmpty() || entries.size() > 100) throw new IllegalArgumentException("自定义词库需要1-100组词语");
        Set<String> seen = new HashSet<>(); List<UndercoverWordCatalog.WordPair> result = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String a = text(entry.get("wordA")).strip(), b = text(entry.get("wordB")).strip();
            if (a.codePointCount(0, a.length()) < 2 || a.codePointCount(0, a.length()) > 20 || b.codePointCount(0, b.length()) < 2 || b.codePointCount(0, b.length()) > 20) throw new IllegalArgumentException("每个自定义词语需要2-20字");
            String na = UndercoverWordCatalog.normalize(a), nb = UndercoverWordCatalog.normalize(b);
            if (na.isBlank() || nb.isBlank() || na.equals(nb)) throw new IllegalArgumentException("词语不能相同或只有标点");
            String key = na.compareTo(nb) < 0 ? na + "\n" + nb : nb + "\n" + na;
            if (!seen.add(key)) throw new IllegalArgumentException("存在重复的自定义词对");
            result.add(new UndercoverWordCatalog.WordPair("custom-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), "custom", "CUSTOM", "ROOM_HOSTED",
                    customKnowledge(a), customKnowledge(b)));
        }
        return result;
    }
    private UndercoverWordCatalog.WordKnowledge customKnowledge(String word) {
        return catalog.ownKnowledge(word).orElse(new UndercoverWordCatalog.WordKnowledge(word, "自定义词，仅根据词语本义描述；没有额外预设答案。", List.of(), List.of(), List.of(word)));
    }
}
