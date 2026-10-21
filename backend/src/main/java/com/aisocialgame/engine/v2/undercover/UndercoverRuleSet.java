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
                case "SELF_DESTRUCT" -> com.aisocialgame.service.ai.v2.AiInformationSources.copy(d, result, "content", "success");
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

    @Override public CommitmentRules.Receipt commitmentAction(GameState state, String actorId, PlayerAction action) {
        return "SELF_DESTRUCT".equalsIgnoreCase(action.getType()) ? null : GameRuleSet.super.commitmentAction(state, actorId, action);
    }

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
        if (!SPEAK_TIMES.contains(text(config.getOrDefault("speakTime", 90)))) return ValidationResult.invalid("发言时长仅支持0、30、60或90秒");
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
        if ("SELF_DESTRUCT".equals(type)) {
            selfDestruct(state, actor, requireContent(action, capability.maxLength()), now);
            return;
        }
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
        event(state, type, actorId, null, actor.getDisplayName() + "：" + content, data);
        completeCurrentSpeech(state, now);
    }

    @Override public void advance(GameState state, LocalDateTime now) {
        // Persisted pre-update rounds may still be waiting for a question or answer.
        // Transition through phase() so outstanding jobs and client tokens become obsolete.
        if (Set.of("CHALLENGE", "RESPONSE").contains(state.getPhase())) {
            for (String key : List.of("questionCursorSeat", "questioners", "challengedPlayers", "challengeIndex", "activeQuestion")) state.getData().remove(key);
            beginVote(state, false, now);
            return;
        }
        if ("SETTLEMENT".equals(state.getPhase()) || !expired(state, now)) return;
        if (VOTING_PHASES.contains(state.getPhase())) {
            for (GamePlayerState actor : alive(state)) if (!votes(state).containsKey(actor.getPlayerId())) {
                event(state, "TIMEOUT", actor.getPlayerId(), null, actor.getDisplayName() + "投票超时，自动弃票。",
                        Map.of("reason", "TIMEOUT", "action", "VOTE", "actorName", actor.getDisplayName()));
                submitVote(state, actor, "abstain");
            }
            resolveVote(state, now);
            return;
        }
        current(state).ifPresent(actor -> event(state, "TIMEOUT", actor.getPlayerId(), null, actor.getDisplayName() + "本次发言超时，自动跳过。", Map.of("reason", "TIMEOUT", "action", "SPEAK", "actorName", actor.getDisplayName())));
        completeCurrentSpeech(state, now);
    }

    @Override public void onAiFailure(GameState state, TurnRequest turn, LocalDateTime now) {
        if (!isTurnCurrent(state, turn)) return;
        onAiFailure(state, turn, now, "REQUEST_FAILED");
    }

    public void onAiFailure(GameState state, TurnRequest turn, LocalDateTime now, String reason) {
        if (!isTurnCurrent(state, turn)) return;
        GamePlayerState actor = player(state, turn.actorId());
        boolean voting = VOTING_PHASES.contains(state.getPhase());
        String label = switch (reason) {
            case "TIMEOUT" -> "超时";
            case "OUTPUT_LIMIT_REACHED" -> "思考达到输出上限";
            case "AI_CREDIT_INSUFFICIENT" -> "额度不足";
            case "BUDGET_RECONCILIATION_REQUIRED" -> "调用待对账";
            default -> "请求失败";
        };
        event(state, "SKIP", actor.getPlayerId(), null,
                actor.getDisplayName() + "因" + label + (voting ? "，自动弃票。" : "，跳过本次发言。"),
                Map.of("reason", reason, "action", voting ? "VOTE" : "SPEAK", "actorName", actor.getDisplayName()));
        if (voting) {
            submitVote(state, actor, "abstain");
            if (votes(state).size() == alive(state).size()) resolveVote(state, now);
        } else completeCurrentSpeech(state, now);
    }

    @Override public List<LegalAction> legalActions(GameState state, String actorId) {
        GamePlayerState actor = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).findFirst().orElse(null);
        if (actor == null || !actor.isAlive() || "SETTLEMENT".equals(state.getPhase())) return List.of();
        List<String> others = alive(state).stream().filter(p -> !p.getPlayerId().equals(actorId)).map(GamePlayerState::getPlayerId).toList();
        List<LegalAction> actions;
        if (VOTING_PHASES.contains(state.getPhase())) {
            if (votes(state).containsKey(actorId)) return List.of();
            List<String> targets = "RUNOFF".equals(state.getPhase()) ? others.stream().filter(strings(state.getData().get("runoffCandidates"))::contains).toList() : others;
            actions = List.of(LegalAction.target("VOTE", "提交投票", targets, 0), LegalAction.simple("SKIP", "弃票"));
        } else {
            if (!Objects.equals(state.getCurrentSeat(), actor.getSeatNumber())) return List.of();
            actions = switch (state.getPhase()) {
                case "DESCRIPTION" -> List.of(LegalAction.text("SPEAK", "描述你的词语", 90));
                case "TIE_DEFENSE" -> List.of(LegalAction.text("SPEAK", "补充描述你的词语", 60), LegalAction.simple("SKIP", "跳过补充描述"));
                default -> List.of();
            };
        }
        if (actions.isEmpty() || "BLANK".equals(actor.getRole())) return actions;
        List<LegalAction> result = new ArrayList<>(actions);
        result.add(LegalAction.text("SELF_DESTRUCT", "自爆猜词", 20));
        return result;
    }

    private void selfDestruct(GameState state, GamePlayerState actor, String guess, LocalDateTime now) {
        require(!UndercoverWordCatalog.normalize(guess).isBlank(), "请填写一个猜测词");
        boolean success = "UNDERCOVER".equals(actor.getRole()) && catalog.matchesAnswer(guess, text(state.getData().get("civilianWord")));
        event(state, "SELF_DESTRUCT", actor.getPlayerId(), null,
                actor.getDisplayName() + "自爆，猜平民词为“" + guess + "”。" + (success ? "自爆成功，卧底阵营获胜。" : "自爆失败，本人出局。"),
                Map.of("content", guess, "actorName", actor.getDisplayName(), "success", success));
        if (success) {
            finish(state, "UNDERCOVER", state.getPlayers().stream().filter(p -> "UNDERCOVER".equals(p.getRole())).map(GamePlayerState::getPlayerId).toList());
            return;
        }
        eliminatePlayer(state, actor);
        if (finishIfWon(state)) return;
        if (VOTING_PHASES.contains(state.getPhase())) {
            // Keep submitted ballots and the clock, but replace observations that still include the eliminated player.
            state.getData().put("phaseSerial", number(state.getData().get("phaseSerial"), 0) + 1);
            if (votes(state).size() == alive(state).size()) resolveVote(state, now);
        } else {
            String previousPhase = state.getPhase();
            LocalDateTime deadline = state.getPhaseEndsAt();
            completeCurrentSpeech(state, now);
            if (previousPhase.equals(state.getPhase())) state.setPhaseEndsAt(deadline);
        }
    }

    @Override public List<TurnRequest> pendingTurns(GameState state) {
        if ("SETTLEMENT".equals(state.getPhase())) return List.of();
        if (VOTING_PHASES.contains(state.getPhase())) return alive(state).stream().filter(RuleSupport::automated)
                .filter(p -> !votes(state).containsKey(p.getPlayerId())).map(p -> turn(state, p.getPlayerId(), "VOTE")).toList();
        return current(state).filter(RuleSupport::automated).filter(p -> !legalActions(state, p.getPlayerId()).isEmpty())
                .map(p -> List.of(turn(state, p.getPlayerId(), "SPEAK")))
                .orElse(List.of());
    }

    @Override public Map<String, Object> publicData(GameState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleVersion", 2); data.put("wordPack", text(state.getData().get("wordPack")));
        data.put("phaseLabel", switch (state.getPhase()) { case "TIE_DEFENSE" -> "补充描述"; case "RUNOFF" -> "复投"; case "DESCRIPTION" -> "描述"; case "VOTING" -> "投票"; default -> "结算"; });
        data.put("descriptionOrder", strings(state.getData().get("descriptionOrder")));
        data.put("questioners", List.of());
        data.put("challengedPlayers", List.of());
        data.put("runoffCandidates", strings(state.getData().get("runoffCandidates")).stream().filter(id -> player(state, id).isAlive()).toList());
        data.put("activeQuestion", Map.of());
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
            case "SPEAK" -> "DESCRIPTION".equals(o.phase())
                    ? AiConversationContext.guide("简洁描述，自行把握透露多少细节。", "DESCRIBE")
                    : AiConversationContext.guide("补充一个尚未用过的词语特征，最多60字；不询问其他玩家，不作身份辩解。", "DESCRIBE");
            case "SELF_DESTRUCT" -> AiConversationContext.guide("自爆并猜平民词，只填写一个答案；失败本人出局。", "GUESS");
            default -> AiConversationContext.guide("按合法行动参与。", "CHOOSE_FOR_SITUATION");
        }, Set.of(), Set.of());
    }

    @Override public String instruction(VisibleObservation observation) {
        return switch (observation.phase()) {
            case "DESCRIPTION" -> "根据局势选择合法动作；描述时通常先概括，自行把握细节，不重复旧描述。";
            case "TIE_DEFENSE" -> "最高票并列：选择合法动作；描述时补充新线索，最多60字，无法补充可SKIP。";
            case "VOTING", "RUNOFF" -> "根据整局公开线索自主选择合法动作，不能只凭发言字数判人。投票或弃票的content留空，不公开解释。";
            default -> "只执行当前合法动作。";
        };
    }

    @Override public PlayerAction fallback(VisibleObservation observation) {
        throw new IllegalStateException("Undercover requires a model decision; recover through onAiFailure");
    }

    @Override public List<String> validateDecision(VisibleObservation observation, AiTurnDecision decision) {
        List<String> errors = new ArrayList<>();
        String word = text(observation.privateFacts().getOrDefault("word", observation.self().get("word")));
        boolean selfDestruct = decision.action() != null && "SELF_DESTRUCT".equals(decision.action().getType());
        if (!selfDestruct && (catalog.containsForbidden(decision.speech(), word)
                || catalog.containsForbidden(decision.action() == null ? "" : decision.action().getContent(), word))
                || catalog.containsForbidden(decision.presentation().toString(), word)) errors.add("SECRET_WORD_LEAK");
        if (!selfDestruct && VOTING_PHASES.contains(observation.phase()) && !decision.speech().isBlank()) errors.add("VOTE_MUST_REMAIN_PRIVATE");
        if (decision.action() != null && "SPEAK".equals(decision.action().getType()) && decision.speech().isBlank()) errors.add("EMPTY_GAME_SPEECH");
        return errors;
    }

    private void beginDescription(GameState state, int startSeat, LocalDateTime now) {
        List<String> order = orderedFrom(state, startSeat).stream().map(GamePlayerState::getPlayerId).toList();
        state.getData().put("roundStartSeat", player(state, order.getFirst()).getSeatNumber());
        state.getData().put("descriptionOrder", order); state.getData().put("descriptionIndex", 0);
        state.getData().put("speakers", new ArrayList<>()); state.getData().put("votes", new LinkedHashMap<>());
        state.getData().put("runoffCandidates", List.of());
        state.getData().put("roundHadElimination", false);
        phase(state, "DESCRIPTION", player(state, order.getFirst()).getSeatNumber(), number(map(state.getData().get("rules")).get("speakTime"), 90), now);
        event(state, "ROUND_START", null, null, "进入第" + state.getRoundNumber() + "轮描述。", Map.of("descriptionOrder", order));
    }

    private void completeCurrentSpeech(GameState state, LocalDateTime now) {
        switch (state.getPhase()) {
            case "DESCRIPTION" -> {
                List<String> order = strings(state.getData().get("descriptionOrder"));
                int index = number(state.getData().get("descriptionIndex"), 0);
                List<String> speakers = new ArrayList<>(strings(state.getData().get("speakers")));
                if (player(state, order.get(index)).isAlive()) speakers.add(order.get(index));
                index++;
                while (index < order.size() && !player(state, order.get(index)).isAlive()) index++;
                state.getData().put("speakers", speakers); state.getData().put("descriptionIndex", index);
                if (index < order.size()) phase(state, "DESCRIPTION", player(state, order.get(index)).getSeatNumber(), number(map(state.getData().get("rules")).get("speakTime"), 90), now);
                else beginVote(state, false, now);
            }
            case "TIE_DEFENSE" -> {
                List<String> candidates = strings(state.getData().get("runoffCandidates"));
                int index = number(state.getData().get("defenseIndex"), 0) + 1;
                while (index < candidates.size() && !player(state, candidates.get(index)).isAlive()) index++;
                state.getData().put("defenseIndex", index);
                if (index < candidates.size()) phase(state, "TIE_DEFENSE", player(state, candidates.get(index)).getSeatNumber(), 60, now);
                else beginVote(state, true, now);
            }
            default -> throw bad("当前阶段无法推进发言");
        }
    }

    private void beginVote(GameState state, boolean runoff, LocalDateTime now) {
        state.getData().put("votes", new LinkedHashMap<>());
        phase(state, runoff ? "RUNOFF" : "VOTING", null, 60, now);
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
        Set<String> targets = alive(state).stream().map(GamePlayerState::getPlayerId).collect(Collectors.toSet());
        Map<String, Long> tally = ballots.values().stream().map(Object::toString).filter(targets::contains).collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
        state.getData().put("lastVoteResult", Map.of("round", state.getRoundNumber(), "phase", state.getPhase(), "votes", new LinkedHashMap<>(ballots), "tally", tally));
        event(state, "VOTE_REVEAL", null, null, "本次投票已全部揭晓。", Map.of("votes", ballots, "tally", tally));
        if (tally.isEmpty()) { noElimination(state, now); return; }
        long most = tally.values().stream().max(Long::compareTo).orElse(0L);
        List<String> leaders = alive(state).stream().map(GamePlayerState::getPlayerId).filter(id -> tally.getOrDefault(id, 0L) == most).toList();
        if (leaders.size() > 1) {
            if ("RUNOFF".equals(state.getPhase())) { noElimination(state, now); return; }
            state.getData().put("runoffCandidates", leaders); state.getData().put("defenseIndex", 0);
            phase(state, "TIE_DEFENSE", player(state, leaders.getFirst()).getSeatNumber(), 60, now);
            event(state, "TIE", null, null, "最高票并列，候选人各补充一次词语描述后复投。", Map.of("candidates", leaders));
            return;
        }
        eliminatePlayer(state, player(state, leaders.getFirst()));
        if (!finishIfWon(state)) nextRound(state, now);
    }

    private void noElimination(GameState state, LocalDateTime now) {
        int count = flag(state.getData().get("roundHadElimination")) ? 0 : number(state.getData().get("noEliminationRounds"), 0) + 1;
        state.getData().put("noEliminationRounds", count);
        event(state, "NO_ELIMINATION", null, null, "本次投票无人出局。", Map.of("consecutiveRounds", count));
        if (count >= 3) finish(state, "DRAW", List.of()); else nextRound(state, now);
    }

    private void eliminatePlayer(GameState state, GamePlayerState actor) {
        actor.setAlive(false);
        state.getData().put("noEliminationRounds", 0);
        state.getData().put("roundHadElimination", true);
        event(state, "ELIMINATED", actor.getPlayerId(), null, actor.getDisplayName() + "出局，身份为" + roleLabel(actor.getRole()) + "。", Map.of("role", actor.getRole()));
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
        String winner = spies == 0 && blank.isEmpty() ? "CIVILIAN"
                : spies > 0 && (spies >= survivors.size() - spies || blank.isEmpty() && survivors.size() <= 3) ? "UNDERCOVER" : null;
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
