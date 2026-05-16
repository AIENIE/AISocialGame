package com.aisocialgame.service.ai;

import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.model.AiDecisionTrace;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.AiDecisionTraceRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiDecisionTraceService {
    private final AiDecisionTraceRepository traceRepository;

    public AiDecisionTraceService(AiDecisionTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @Transactional
    public AiDecisionTrace record(GameState state,
                                  GamePlayerState actor,
                                  String action,
                                  AiDecisionResult result,
                                  Map<String, Object> rawOutput,
                                  AiChatResult aiResponse,
                                  long latencyMs,
                                  Map<String, Object> belief,
                                  Map<String, Object> memory,
                                  List<String> qualityFlags,
                                  String inputSummary) {
        AiDecisionTrace trace = new AiDecisionTrace();
        trace.setRoomId(state.getRoomId());
        trace.setGameId(state.getGameId());
        trace.setPhase(state.getPhase());
        trace.setRoundNumber(state.getRoundNumber());
        trace.setAction(action);
        trace.setActorPlayerId(actor.getPlayerId());
        trace.setPersonaId(StringUtils.hasText(actor.getPersonaId()) ? actor.getPersonaId() : "unknown");
        trace.setRoleKey(StringUtils.hasText(actor.getRole()) ? actor.getRole() : "UNKNOWN");
        trace.setModelKey(aiResponse != null ? aiResponse.modelKey() : "");
        trace.setPromptTokens(aiResponse != null ? aiResponse.promptTokens() : 0);
        trace.setCompletionTokens(aiResponse != null ? aiResponse.completionTokens() : 0);
        trace.setLatencyMs(latencyMs);
        trace.setFallback(result.fallback());
        trace.setValidDecision(qualityFlags == null || qualityFlags.stream().noneMatch(flag -> flag.startsWith("ILLEGAL") || flag.contains("SECRET_LEAK")));
        trace.setConfidence(result.confidence());
        trace.setTargetPlayerId(result.targetPlayerId());
        trace.setNightAction(result.action());
        trace.setReason(trim(result.reason(), 512));
        trace.setOutputSummary(trim(outputSummary(result), 512));
        trace.setInputSummary(trim(inputSummary, 1024));
        trace.setBeliefSnapshot(belief);
        trace.setMemorySnapshot(memory);
        trace.setRawOutput(rawOutput);
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("flags", qualityFlags == null ? List.of() : qualityFlags);
        quality.put("fallback", result.fallback());
        trace.setQuality(quality);
        return traceRepository.save(trace);
    }

    @Transactional(readOnly = true)
    public Page<AiDecisionTrace> search(String roomId,
                                        String gameId,
                                        String personaId,
                                        String action,
                                        Boolean fallback,
                                        String qualityFlag,
                                        int page,
                                        int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(size, 1), 100);
        return traceRepository.findAll(spec(roomId, gameId, personaId, action, fallback, qualityFlag),
                PageRequest.of(effectivePage, effectiveSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    public Map<String, Object> safeLogMetadata(AiDecisionTrace trace, List<String> evidence, String reflection) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("aiTraceId", trace.getId());
        metadata.put("aiFallback", trace.isFallback());
        metadata.put("aiQualityFlags", trace.getQuality().getOrDefault("flags", List.of()));
        metadata.put("aiLatencyMs", trace.getLatencyMs());
        metadata.put("aiModelKey", trace.getModelKey());
        metadata.put("aiReason", trace.getReason());
        metadata.put("aiEvidence", evidence == null ? List.of() : evidence.stream().limit(3).toList());
        metadata.put("aiReflection", trim(reflection, 180));
        return metadata;
    }

    private Specification<AiDecisionTrace> spec(String roomId,
                                                String gameId,
                                                String personaId,
                                                String action,
                                                Boolean fallback,
                                                String qualityFlag) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(roomId)) {
                predicates.add(cb.equal(root.get("roomId"), roomId));
            }
            if (StringUtils.hasText(gameId)) {
                predicates.add(cb.equal(root.get("gameId"), gameId));
            }
            if (StringUtils.hasText(personaId)) {
                predicates.add(cb.equal(root.get("personaId"), personaId));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (fallback != null) {
                predicates.add(cb.equal(root.get("fallback"), fallback));
            }
            if (StringUtils.hasText(qualityFlag)) {
                predicates.add(cb.like(root.get("quality").as(String.class), "%" + qualityFlag + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private String outputSummary(AiDecisionResult result) {
        if (StringUtils.hasText(result.content())) {
            return result.content();
        }
        if (StringUtils.hasText(result.action())) {
            return result.action() + " -> " + result.targetPlayerId();
        }
        if (StringUtils.hasText(result.targetPlayerId())) {
            return "VOTE -> " + result.targetPlayerId();
        }
        return "";
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
