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

    public ReplayArchiveService(GameArchiveRepository gameArchiveRepository,
                                GameEventRepository gameEventRepository,
                                AiDecisionTraceRepository aiDecisionTraceRepository) {
        this.gameArchiveRepository = gameArchiveRepository;
        this.gameEventRepository = gameEventRepository;
        this.aiDecisionTraceRepository = aiDecisionTraceRepository;
    }

    @Transactional
    public GameArchive archiveFinishedGame(GameState state, Room room) {
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
        archive.setPlayerCount(state.getPlayers().size());
        archive.setTotalRounds(state.getRoundNumber());
        archive.setStartedAt(startedAt);
        archive.setFinishedAt(finishedAt);
        archive.setDurationSeconds(Math.max(0, Duration.between(startedAt, finishedAt).toSeconds()));
        archive.setPlayersSnapshot(Map.of("players", playerSnapshots(state)));
        archive.setAiQualitySummary(aiQualitySummary(state.getRoomId()));
        archive.setEventCount(gameEventRepository.countByArchiveId(archiveId));
        archive.setSummary("对局结束，获胜方：" + archive.getWinner());
        return gameArchiveRepository.save(archive);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReplayArchiveView> list(String gameId, int page, int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(size, 1), 100);
        var pageable = PageRequest.of(effectivePage, effectiveSize, Sort.by(Sort.Direction.DESC, "finishedAt", "createdAt"));
        var result = gameId == null || gameId.isBlank()
                ? gameArchiveRepository.findAll(pageable)
                : gameArchiveRepository.findByGameId(gameId, pageable);
        return new PagedResponse<>(
                result.getContent().stream().map(ReplayArchiveView::new).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public ReplayArchiveView detail(String archiveId) {
        return new ReplayArchiveView(findArchive(archiveId));
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

    private Map<String, Object> aiQualitySummary(String roomId) {
        List<?> traces = aiDecisionTraceRepository.findByRoomIdOrderByIdDesc(roomId);
        long fallbackCount = traces.stream()
                .filter(item -> item instanceof com.aisocialgame.model.AiDecisionTrace trace && trace.isFallback())
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceCount", traces.size());
        summary.put("fallbackCount", fallbackCount);
        return summary;
    }
}
