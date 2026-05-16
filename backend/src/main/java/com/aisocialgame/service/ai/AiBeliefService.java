package com.aisocialgame.service.ai;

import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiBeliefService {

    public Map<String, Object> buildBelief(GameState state, GamePlayerState actor) {
        Map<String, Object> belief = new LinkedHashMap<>();
        belief.put("visibilityScope", visibilityScope(state, actor));
        belief.put("round", state.getRoundNumber());
        belief.put("phase", state.getPhase());
        belief.put("players", playerBeliefs(state, actor));
        belief.put("keyEvidence", keyEvidence(state));
        return belief;
    }

    public void rememberBelief(GameState state, GamePlayerState actor, Map<String, Object> belief) {
        Map<String, Object> data = state.getData();
        Map<String, Object> all = mapAt(data, "aiBeliefs");
        all.put(actor.getPlayerId(), belief);
        data.put("aiBeliefs", all);
    }

    private String visibilityScope(GameState state, GamePlayerState actor) {
        if ("werewolf".equals(state.getGameId())) {
            if (actor.getRole() != null && actor.getRole().startsWith("WEREWOLF")) {
                return "PUBLIC_PLUS_WOLF_TEAM";
            }
            if ("SEER".equals(actor.getRole())) {
                return "PUBLIC_PLUS_OWN_SEER_RESULTS";
            }
            return "PUBLIC_ONLY";
        }
        return "OWN_WORD_PLUS_PUBLIC_SPEECH";
    }

    private List<Map<String, Object>> playerBeliefs(GameState state, GamePlayerState actor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GamePlayerState player : state.getPlayers()) {
            if (player.getPlayerId().equals(actor.getPlayerId())) {
                continue;
            }
            int suspicion = suspicionScore(state, player);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("playerId", player.getPlayerId());
            item.put("seatNumber", player.getSeatNumber());
            item.put("displayName", player.getDisplayName());
            item.put("alive", player.isAlive());
            item.put("suspicion", suspicion);
            item.put("trust", Math.max(0, 100 - suspicion));
            item.put("clues", cluesFor(state, player));
            result.add(item);
        }
        return result;
    }

    private int suspicionScore(GameState state, GamePlayerState player) {
        int score = player.isAlive() ? 35 : 0;
        int speechLength = 0;
        int voteCount = 0;
        if (state.getLogs() != null) {
            for (GameLogEntry log : state.getLogs()) {
                String message = log.getMessage();
                if (!StringUtils.hasText(message)) {
                    continue;
                }
                if ("speak".equals(log.getType()) && startsWithName(message, player.getDisplayName())) {
                    speechLength += message.length();
                }
                if ("vote".equals(log.getType()) && message.contains(player.getDisplayName())) {
                    voteCount++;
                }
            }
        }
        if (speechLength < 18 && player.isAlive()) {
            score += 25;
        } else if (speechLength > 80) {
            score -= 8;
        }
        score += Math.min(20, voteCount * 6);
        return Math.max(0, Math.min(100, score));
    }

    private List<String> cluesFor(GameState state, GamePlayerState player) {
        List<String> clues = new ArrayList<>();
        if (state.getLogs() != null) {
            for (GameLogEntry log : state.getLogs()) {
                String message = log.getMessage();
                if (StringUtils.hasText(message) && (startsWithName(message, player.getDisplayName()) || message.contains(player.getDisplayName()))) {
                    clues.add(trim(message, 90));
                }
            }
        }
        if (clues.isEmpty() && player.isAlive()) {
            clues.add("暂无强证据，保持观察");
        }
        return tail(clues, 4);
    }

    private List<String> keyEvidence(GameState state) {
        List<String> evidence = new ArrayList<>();
        if (state.getLogs() != null) {
            for (GameLogEntry log : state.getLogs()) {
                if (StringUtils.hasText(log.getMessage()) && ("speak".equals(log.getType()) || "vote".equals(log.getType()))) {
                    evidence.add(trim(log.getMessage(), 90));
                }
            }
        }
        return tail(evidence, 8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> data, String key) {
        Object raw = data.get(key);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(k.toString(), v));
            return copy;
        }
        return new HashMap<>();
    }

    private boolean startsWithName(String message, String name) {
        return message.startsWith(name + "：") || message.startsWith(name + "（AI）：") || message.startsWith(name + "（托管）：");
    }

    private List<String> tail(List<String> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        return values.subList(values.size() - limit, values.size());
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
