package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.model.AiDecisionTrace;
import com.aisocialgame.model.AiPersonaMemory;
import com.aisocialgame.model.CommunityPost;
import com.aisocialgame.model.PlayerStats;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.credit.CreditRedeemCode;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.repository.PlayerStatsRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.credit.CreditRedeemCodeRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DemoSeedService implements ApplicationRunner {
    private static final String DEMO_UNDERCOVER_ROOM_ID = "demo-undercover-room";
    private static final String DEMO_WEREWOLF_ROOM_ID = "demo-werewolf-room";
    private static final String DEMO_AI_ROOM_ID = "demo-ai-quality-room";

    private final AppProperties appProperties;
    private final CommunityPostRepository communityPostRepository;
    private final RoomRepository roomRepository;
    private final PlayerStatsRepository playerStatsRepository;
    private final CreditRedeemCodeRepository creditRedeemCodeRepository;
    private final AiPersonaMemoryRepository aiPersonaMemoryRepository;
    private final AiDecisionTraceRepository aiDecisionTraceRepository;

    public DemoSeedService(AppProperties appProperties,
                           CommunityPostRepository communityPostRepository,
                           RoomRepository roomRepository,
                           PlayerStatsRepository playerStatsRepository,
                           CreditRedeemCodeRepository creditRedeemCodeRepository,
                           AiPersonaMemoryRepository aiPersonaMemoryRepository,
                           AiDecisionTraceRepository aiDecisionTraceRepository) {
        this.appProperties = appProperties;
        this.communityPostRepository = communityPostRepository;
        this.roomRepository = roomRepository;
        this.playerStatsRepository = playerStatsRepository;
        this.creditRedeemCodeRepository = creditRedeemCodeRepository;
        this.aiPersonaMemoryRepository = aiPersonaMemoryRepository;
        this.aiDecisionTraceRepository = aiDecisionTraceRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!appProperties.getDemoSeed().isEnabled()) {
            return;
        }
        seedCommunityPosts();
        seedRooms();
        seedRankings();
        seedRedeemCodes();
        seedAiQualitySamples();
    }

    private void seedCommunityPosts() {
        seedPost(
                "demo-post-ai-quality",
                "AI 质检观察员",
                "本地演示：AI 发言、投票和夜晚行动都会生成服务端 trace，运营可以在管理台查看质量标记和 Persona 记忆。",
                List.of("AI质检", "本地演示"),
                18,
                3
        );
        seedPost(
                "demo-post-undercover",
                "卧底房主",
                "本地演示：谁是卧底房间已经预置，可以直接加入、补 AI 并开局。",
                List.of("谁是卧底", "开箱即用"),
                12,
                1
        );
        seedPost(
                "demo-post-werewolf",
                "狼人杀复盘员",
                "本地演示：狼人杀等待房支持 6 人快速开局，适合验收 AI 夜晚行动和白天投票。",
                List.of("狼人杀", "复盘"),
                9,
                2
        );
    }

    private void seedPost(String id, String author, String content, List<String> tags, int likes, int comments) {
        if (communityPostRepository.existsById(id)) {
            return;
        }
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setAuthorName(author);
        post.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + id);
        post.setContent(content);
        post.setTags(tags);
        post.setLikes(likes);
        post.setComments(comments);
        communityPostRepository.save(post);
    }

    private void seedRooms() {
        if (!roomRepository.existsById(DEMO_UNDERCOVER_ROOM_ID)) {
            Room room = new Room(
                    DEMO_UNDERCOVER_ROOM_ID,
                    "undercover",
                    "demo-谁是卧底-开箱即用房",
                    RoomStatus.WAITING,
                    6,
                    false,
                    null,
                    "text",
                    new HashMap<>(Map.of("playerCount", 6, "spyMode", "auto", "hasBlank", false, "wordPack", "daily", "speakTime", 60))
            );
            room.setSeats(new ArrayList<>(List.of(
                    humanSeat(0, "demo-player-undercover-host", "演示房主"),
                    aiSeat(1, "ai1", "福尔摩斯"),
                    aiSeat(2, "ai2", "小丑")
            )));
            roomRepository.save(room);
        }

        if (!roomRepository.existsById(DEMO_WEREWOLF_ROOM_ID)) {
            Room room = new Room(
                    DEMO_WEREWOLF_ROOM_ID,
                    "werewolf",
                    "demo-狼人杀-标准演示房",
                    RoomStatus.WAITING,
                    6,
                    false,
                    null,
                    "text",
                    new HashMap<>(Map.of("playerCount", 6, "template", "standard", "witchRule", "first_night", "winCondition", "side", "speechTime", 90, "hasLastWords", "first_night"))
            );
            room.setSeats(new ArrayList<>(List.of(
                    humanSeat(0, "demo-player-werewolf-host", "演示预言家"),
                    aiSeat(1, "ai1", "福尔摩斯"),
                    aiSeat(2, "ai2", "小丑"),
                    aiSeat(3, "ai3", "华生")
            )));
            roomRepository.save(room);
        }
    }

    private RoomSeat humanSeat(int seatNumber, String playerId, String displayName) {
        return new RoomSeat(seatNumber, playerId, displayName, false, null, "https://api.dicebear.com/7.x/avataaars/svg?seed=" + playerId, true, seatNumber == 0);
    }

    private RoomSeat aiSeat(int seatNumber, String personaId, String displayName) {
        return new RoomSeat(seatNumber, "demo-" + personaId + "-" + seatNumber, displayName, true, personaId, "https://api.dicebear.com/7.x/avataaars/svg?seed=" + personaId, true, false);
    }

    private void seedRankings() {
        seedRank("demo-rank-luna-total", "total", "露娜观察员", 42, 23, 735);
        seedRank("demo-rank-sherlock-total", "total", "福尔摩斯搭档", 38, 21, 690);
        seedRank("demo-rank-watson-total", "total", "华生队友", 31, 16, 520);
        seedRank("demo-rank-undercover-a", "undercover", "卧底推理手", 18, 11, 315);
        seedRank("demo-rank-werewolf-a", "werewolf", "银月猎人", 20, 12, 340);
    }

    private void seedRank(String playerId, String gameId, String displayName, int gamesPlayed, int wins, int score) {
        String id = playerId + ":" + gameId;
        if (playerStatsRepository.existsById(id)) {
            return;
        }
        PlayerStats stats = new PlayerStats();
        stats.setId(id);
        stats.setPlayerId(playerId);
        stats.setGameId(gameId);
        stats.setDisplayName(displayName);
        stats.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + playerId);
        stats.setGamesPlayed(gamesPlayed);
        stats.setWins(wins);
        stats.setScore(score);
        playerStatsRepository.save(stats);
    }

    private void seedRedeemCodes() {
        seedRedeemCode("DEMO-LOCAL-1000", "CREDIT_TYPE_PERMANENT", 1000);
        seedRedeemCode("DEMO-LOCAL-TEMP-300", "CREDIT_TYPE_TEMP", 300);
    }

    private void seedRedeemCode(String code, String creditType, long tokens) {
        if (creditRedeemCodeRepository.findByCode(code).isPresent()) {
            return;
        }
        CreditRedeemCode redeemCode = new CreditRedeemCode();
        redeemCode.setCode(code);
        redeemCode.setCreditType(creditType);
        redeemCode.setTokens(tokens);
        redeemCode.setActive(true);
        redeemCode.setValidFrom(Instant.now().minusSeconds(3600));
        redeemCode.setValidUntil(Instant.now().plusSeconds(60L * 60 * 24 * 365));
        redeemCode.setMaxRedemptions(1000);
        creditRedeemCodeRepository.save(redeemCode);
    }

    private void seedAiQualitySamples() {
        seedMemory("ai1", "undercover", "UNDERCOVER",
                "保持冷静短句，优先复述可验证细节，避免一开始暴露词义边界。",
                "先观察两个相邻玩家描述是否同属一个词域，再决定跟随或制造轻微分歧。",
                "曾在首轮给出过于精确的物品属性，后续应改为类别描述。",
                "常用“我先保留”“这个描述范围偏窄”等克制表达。",
                7);
        seedMemory("ai3", "werewolf", "SEER",
                "作为预言家时先给验人结论，再说明查验顺序，语气温和但要明确站边。",
                "优先查验强带节奏位；若被对跳，要求对方给出完整警徽流。",
                "曾遗漏夜晚查验结果，应在白天第一轮主动报信息。",
                "多用解释型句式，帮助新玩家理解投票理由。",
                5);

        if (!aiDecisionTraceRepository.existsByRoomIdAndActionAndPersonaId(DEMO_AI_ROOM_ID, "SPEAK", "ai1")) {
            AiDecisionTrace trace = baseTrace("undercover", "DESCRIPTION", "SPEAK", "demo-ai1", "ai1", "UNDERCOVER");
            trace.setRoomId(DEMO_AI_ROOM_ID);
            trace.setModelKey("demo-model");
            trace.setLatencyMs(620);
            trace.setConfidence(0.78);
            trace.setReason("根据前两位玩家描述都偏向饮品口感，选择给出模糊但一致的描述。");
            trace.setInputSummary("第1轮描述阶段，已听到2条玩家发言，身份为卧底。");
            trace.setOutputSummary("给出与饮品相关但不暴露差异词的描述。");
            trace.setBeliefSnapshot(Map.of("suspects", List.of("seat4"), "selfRisk", "medium"));
            trace.setMemorySnapshot(Map.of("persona", "冷静短句", "recentLesson", "避免过精确"));
            trace.setQuality(Map.of("flags", List.of("demo_sample"), "score", 0.86, "notes", "表达有依据且未泄露隐藏词。"));
            trace.setRawOutput(Map.of("content", "我先说一个比较宽的感受：它更像日常会入口的东西，重点在口感而不是外形。"));
            aiDecisionTraceRepository.save(trace);
        }

        if (!aiDecisionTraceRepository.existsByRoomIdAndActionAndPersonaId(DEMO_AI_ROOM_ID, "VOTE", "ai3")) {
            AiDecisionTrace trace = baseTrace("werewolf", "DAY_VOTE", "VOTE", "demo-ai3", "ai3", "SEER");
            trace.setRoomId(DEMO_AI_ROOM_ID);
            trace.setTargetPlayerId("demo-player-wolf");
            trace.setModelKey("demo-model");
            trace.setLatencyMs(710);
            trace.setConfidence(0.82);
            trace.setReason("目标玩家先后发言对夜晚信息解释不一致，且回避投票理由。");
            trace.setInputSummary("第2天投票阶段，场上6人存活，已出现两组对跳信息。");
            trace.setOutputSummary("投向发言矛盾且缺少验人链的玩家。");
            trace.setBeliefSnapshot(Map.of("trusted", List.of("demo-human-1"), "suspects", List.of("demo-player-wolf")));
            trace.setMemorySnapshot(Map.of("strategy", "先给结论再给查验顺序"));
            trace.setQuality(Map.of("flags", List.of("demo_sample", "evidence_based"), "score", 0.9));
            trace.setRawOutput(Map.of("targetPlayerId", "demo-player-wolf", "reason", "前后视角不一致"));
            aiDecisionTraceRepository.save(trace);
        }
    }

    private void seedMemory(String personaId,
                            String gameId,
                            String roleKey,
                            String memorySummary,
                            String strategyNotes,
                            String mistakeNotes,
                            String speechPatterns,
                            int gamesPlayed) {
        AiPersonaMemory memory = aiPersonaMemoryRepository.findByPersonaIdAndGameIdAndRoleKey(personaId, gameId, roleKey)
                .orElseGet(() -> new AiPersonaMemory(personaId, gameId, roleKey));
        if (memory.getId() != null) {
            return;
        }
        memory.setMemorySummary(memorySummary);
        memory.setStrategyNotes(strategyNotes);
        memory.setMistakeNotes(mistakeNotes);
        memory.setSpeechPatterns(speechPatterns);
        memory.setGamesPlayed(gamesPlayed);
        aiPersonaMemoryRepository.save(memory);
    }

    private AiDecisionTrace baseTrace(String gameId, String phase, String action, String actorPlayerId, String personaId, String roleKey) {
        AiDecisionTrace trace = new AiDecisionTrace();
        trace.setGameId(gameId);
        trace.setPhase(phase);
        trace.setRoundNumber(1);
        trace.setAction(action);
        trace.setActorPlayerId(actorPlayerId);
        trace.setPersonaId(personaId);
        trace.setRoleKey(roleKey);
        trace.setFallback(false);
        trace.setValidDecision(true);
        trace.setPromptTokens(1200);
        trace.setCompletionTokens(180);
        return trace;
    }
}
