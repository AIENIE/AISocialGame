package com.aisocialgame.service.ai;

import com.aisocialgame.model.AiPersonaMemory;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiReflectionService {
    private final AiPersonaMemoryRepository personaMemoryRepository;

    public AiReflectionService(AiPersonaMemoryRepository personaMemoryRepository) {
        this.personaMemoryRepository = personaMemoryRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> memorySnapshot(GameState state, GamePlayerState actor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        String roleKey = roleKey(actor);
        snapshot.put("roleKey", roleKey);
        snapshot.put("shortMemory", shortMemory(state, actor));
        personaMemoryRepository.findByPersonaIdAndGameIdAndRoleKey(personaId(actor), state.getGameId(), roleKey)
                .ifPresent(memory -> {
                    snapshot.put("memorySummary", memory.getMemorySummary());
                    snapshot.put("strategyNotes", memory.getStrategyNotes());
                    snapshot.put("mistakeNotes", memory.getMistakeNotes());
                    snapshot.put("speechPatterns", memory.getSpeechPatterns());
                    snapshot.put("gamesPlayed", memory.getGamesPlayed());
                });
        return snapshot;
    }

    public String summarizeDecision(GameState state, GamePlayerState actor, AiDecisionResult result, List<String> qualityFlags) {
        String action = StringUtils.hasText(result.action()) ? result.action() : StringUtils.hasText(result.targetPlayerId()) ? "VOTE" : "SPEECH";
        String reason = StringUtils.hasText(result.reason()) ? result.reason() : "无显式理由";
        String quality = qualityFlags == null || qualityFlags.isEmpty() ? "质量正常" : "需复盘:" + String.join(",", qualityFlags);
        return trim("第" + state.getRoundNumber() + "轮 " + action + "，依据：" + reason + "，" + quality, 180);
    }

    public void rememberShortReflection(GameState state, GamePlayerState actor, String reflection) {
        Map<String, Object> memories = mapAt(state.getData(), "aiShortMemories");
        memories.put(actor.getPlayerId(), reflection);
        state.getData().put("aiShortMemories", memories);
    }

    @Transactional
    public AiPersonaMemory updatePersonaMemory(GameState state, GamePlayerState actor, AiDecisionResult result, String reflection, List<String> qualityFlags) {
        String personaId = personaId(actor);
        String roleKey = roleKey(actor);
        AiPersonaMemory memory = personaMemoryRepository.findByPersonaIdAndGameIdAndRoleKey(personaId, state.getGameId(), roleKey)
                .orElseGet(() -> new AiPersonaMemory(personaId, state.getGameId(), roleKey));
        memory.setGamesPlayed(memory.getGamesPlayed() + 1);
        memory.setMemorySummary(append(memory.getMemorySummary(), reflection, 900));
        if (StringUtils.hasText(result.reason())) {
            memory.setStrategyNotes(append(memory.getStrategyNotes(), result.reason(), 700));
        }
        if (qualityFlags != null && !qualityFlags.isEmpty()) {
            memory.setMistakeNotes(append(memory.getMistakeNotes(), String.join(",", qualityFlags), 700));
        }
        if (StringUtils.hasText(result.content())) {
            memory.setSpeechPatterns(append(memory.getSpeechPatterns(), result.content(), 700));
        }
        return personaMemoryRepository.save(memory);
    }

    @Transactional
    public void resetMemory(Long id) {
        personaMemoryRepository.findById(id).ifPresent(memory -> {
            memory.setMemorySummary("");
            memory.setStrategyNotes("");
            memory.setMistakeNotes("");
            memory.setSpeechPatterns("");
            memory.setGamesPlayed(0);
            personaMemoryRepository.save(memory);
        });
    }

    private String shortMemory(GameState state, GamePlayerState actor) {
        Object raw = mapAt(state.getData(), "aiShortMemories").get(actor.getPlayerId());
        return raw == null ? "" : raw.toString();
    }

    private String personaId(GamePlayerState actor) {
        return StringUtils.hasText(actor.getPersonaId()) ? actor.getPersonaId() : "unknown";
    }

    private String roleKey(GamePlayerState actor) {
        return StringUtils.hasText(actor.getRole()) ? actor.getRole() : "UNKNOWN";
    }

    private String append(String current, String next, int maxLength) {
        String value = StringUtils.hasText(current) ? current.strip() + "\n" + next : next;
        return trim(value, maxLength);
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(Math.max(0, value.length() - maxLength));
    }

    private Map<String, Object> mapAt(Map<String, Object> data, String key) {
        Object raw = data.get(key);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(k.toString(), v));
            return copy;
        }
        return new HashMap<>();
    }
}
