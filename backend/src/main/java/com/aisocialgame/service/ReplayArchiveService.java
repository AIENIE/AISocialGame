package com.aisocialgame.service;

import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.ReplayArchiveView;
import com.aisocialgame.dto.ReplayDetailResponse;
import com.aisocialgame.dto.ReplayEventView;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameArchive;
import com.aisocialgame.model.GameEvent;
import com.aisocialgame.model.GameEventVisibility;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import com.aisocialgame.repository.GameArchiveRepository;
import com.aisocialgame.repository.GameEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReplayArchiveService {
    private final GameArchiveRepository gameArchiveRepository;
    private final GameEventRepository gameEventRepository;
    private final AiDecisionTraceRepository aiDecisionTraceRepository;
    private final com.aisocialgame.repository.ArchiveParticipantRepository participants;

    public ReplayArchiveService(GameArchiveRepository gameArchiveRepository,
                                GameEventRepository gameEventRepository,
                                AiDecisionTraceRepository aiDecisionTraceRepository, com.aisocialgame.repository.ArchiveParticipantRepository participants) {
        this.participants = participants;
        this.gameArchiveRepository = gameArchiveRepository;
        this.gameEventRepository = gameEventRepository;
        this.aiDecisionTraceRepository = aiDecisionTraceRepository;
    }

    @Transactional
    public GameArchive archiveFinishedGame(GameState state, Room room) {
        if (!"SETTLEMENT".equals(state.getPhase())) throw new ApiException(HttpStatus.CONFLICT, "对局尚未结束，不能发布回放");
        String archiveId = String.valueOf(state.getData().get(GameEventRecorder.ARCHIVE_ID_KEY));
        if (archiveId == null || archiveId.isBlank() || "null".equals(archiveId)) {
            return null;
        }
        if (gameArchiveRepository.existsById(archiveId)) {
            return gameArchiveRepository.findById(archiveId).orElseThrow();
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        LocalDateTime startedAt = state.getCreatedAt() != null ? state.getCreatedAt() : finishedAt;
        GameArchive archive = new GameArchive();
        archive.setId(archiveId);
        archive.setRoomId(state.getRoomId());
        archive.setGameId(state.getGameId());
        archive.setRoomName(room.getName());
        archive.setWinner((String) state.getData().get("winner"));
        var accessSnapshot = com.aisocialgame.engine.v2.RuleSupport.map(state.getData().get("accessSnapshot"));
        archive.setPublicReplay(accessSnapshot.get("publicReplay") instanceof Boolean value ? value : null);
        archive.setHostUserId(accessSnapshot.get("hostUserId") instanceof String host && !host.isBlank() ? host : null);
        archive.setPlayerCount(state.getPlayers().size());
        archive.setTotalRounds(state.getRoundNumber());
        archive.setStartedAt(startedAt);
        archive.setFinishedAt(finishedAt);
        archive.setDurationSeconds(Math.max(0, Duration.between(startedAt, finishedAt).toSeconds()));
        archive.setPlayersSnapshot(Map.of("players", playerSnapshots(state), "ruleVersion", state.getData().getOrDefault("ruleVersion", 1),
                "rules", state.getData().getOrDefault("rules", Map.of()), "knowledgeVersion", state.getData().getOrDefault("knowledgeVersion", "legacy"), "promptVersion", state.getData().getOrDefault("promptVersion", "legacy")));
        archive.setAiQualitySummary(aiQualitySummary(state.getRoomId(), archiveId));
        archive.setEventCount(gameEventRepository.countByArchiveId(archiveId));
        archive.setSummary("对局结束，获胜方：" + archive.getWinner());
        GameArchive saved = gameArchiveRepository.save(archive);
        var playerIds = new java.util.LinkedHashSet<String>();
        state.getPlayers().forEach(player -> playerIds.add(player.getPlayerId()));
        if (accessSnapshot.get("participants") instanceof List<?> original) original.stream().filter(String.class::isInstance).map(String.class::cast).forEach(playerIds::add);
        participants.saveAll(playerIds.stream().map(player -> new com.aisocialgame.model.ArchiveParticipant(archiveId, player)).toList());
        return saved;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReplayArchiveView> search(String gameId, String playerId, LocalDateTime from, LocalDateTime to, int page, int size, String viewerId) {
        if (from != null && to != null && from.isAfter(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "时间范围无效");
        int limit = Math.min(Math.max(size, 1), 100);
        var pageable = PageRequest.of(Math.max(page, 0), limit, Sort.by(Sort.Direction.DESC, "finishedAt", "id"));
        var result = gameArchiveRepository.searchVisible(gameId == null || gameId.isBlank() ? null : gameId, playerId, viewerId, from, to, pageable);
        return new PagedResponse<>(result.getContent().stream().map(ReplayArchiveView::new).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private boolean participant(GameArchive archive, String playerId) {
        return playerId != null && participants.existsById(new com.aisocialgame.model.ArchiveParticipant.Key(archive.getId(), playerId));
    }

    @Transactional(readOnly = true)
    public ReplayArchiveView detail(String archiveId, String viewerId) {
        GameArchive archive = findArchive(archiveId);
        requireRead(archive, viewerId);
        return new ReplayArchiveView(archive);
    }

    @Transactional(readOnly = true)
    public ReplayDetailResponse events(String archiveId, String viewMode, String viewerPlayerId) {
        GameArchive archive = findArchive(archiveId);
        String effectiveMode = viewMode == null || viewMode.isBlank() ? "PUBLIC" : viewMode.toUpperCase(Locale.ROOT);
        List<ReplayEventView> events = gameEventRepository.findByArchiveIdOrderBySeqAsc(archiveId).stream()
                .filter(event -> isVisible(event, effectiveMode, viewerPlayerId))
                .map(ReplayEventView::new)
                .toList();
        return new ReplayDetailResponse(new ReplayArchiveView(archive), effectiveMode, events);
    }

    /** Every replay private perspective is the authenticated player, never a query-string identity. */
    @Transactional(readOnly = true)
    public ReplayDetailResponse authorizedEvents(String archiveId, String viewMode, String requestedPlayerId, String authenticatedPlayerId) {
        GameArchive archive = findArchive(archiveId);
        requireRead(archive, authenticatedPlayerId);
        String mode = viewMode == null || viewMode.isBlank() ? "PUBLIC" : viewMode.toUpperCase(Locale.ROOT);
        if ("GOD".equals(mode) || (requestedPlayerId != null && !requestedPlayerId.equals(authenticatedPlayerId)))
            throw new ApiException(HttpStatus.FORBIDDEN, "无权读取该回放视角");
        if (!List.of("PUBLIC", "PLAYER").contains(mode)) throw new ApiException(HttpStatus.BAD_REQUEST, "回放视角无效");
        if ("PLAYER".equals(mode)) {
            if (authenticatedPlayerId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
            if (!participant(archive, authenticatedPlayerId)) throw new ApiException(HttpStatus.FORBIDDEN, "只能读取本人参与的回放视角");
        }
        var response = events(archiveId, mode, authenticatedPlayerId);
        response.setAvailableViews(authenticatedPlayerId != null && participant(archive, authenticatedPlayerId) ? List.of("PUBLIC", "PLAYER") : List.of("PUBLIC"));
        return response;
    }

    private void requireRead(GameArchive archive, String viewerId) {
        if (viewerId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!Boolean.TRUE.equals(archive.getPublicReplay()) && !viewerId.equals(archive.getHostUserId()) && !participant(archive, viewerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权访问该私密或历史权限未知的回放");
        }
    }

    private GameArchive findArchive(String archiveId) {
        return gameArchiveRepository.findById(archiveId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "回放不存在"));
    }

    private boolean isVisible(GameEvent event, String viewMode, String viewerPlayerId) {
        if ("GOD".equals(viewMode)) {
            return true;
        }
        if (event.getVisibility() == GameEventVisibility.PUBLIC) {
            return true;
        }
        if ("PLAYER".equals(viewMode) && event.getVisibility() == GameEventVisibility.PRIVATE && viewerPlayerId != null && !viewerPlayerId.isBlank()) {
            return event.getVisibleToPlayerIds().contains(viewerPlayerId);
        }
        return false;
    }

    private List<Map<String, Object>> playerSnapshots(GameState state) {
        return state.getPlayers().stream().map(player -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("playerId", player.getPlayerId());
            item.put("displayName", player.getDisplayName());
            item.put("seatNumber", player.getSeatNumber());
            item.put("ai", player.isAi());
            item.put("personaId", player.getPersonaId());
            item.put("alive", player.isAlive());
            item.put("role", player.getRole());
            item.put("word", player.getWord());
            return item;
        }).toList();
    }

    private Map<String, Object> aiQualitySummary(String roomId, String archiveId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceCount", aiDecisionTraceRepository.countByInstanceId(archiveId));
        summary.put("fallbackCount", aiDecisionTraceRepository.countByInstanceIdAndFallbackTrue(archiveId));
        summary.put("association", "INSTANCE_ID");
        summary.put("legacyUnattributed", aiDecisionTraceRepository.countByRoomIdAndInstanceIdIsNull(roomId));
        return summary;
    }
}
