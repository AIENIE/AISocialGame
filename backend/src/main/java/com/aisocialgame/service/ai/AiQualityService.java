package com.aisocialgame.service.ai;

import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiQualityService {

    public List<String> evaluate(GameState state, GamePlayerState actor, AiDecisionResult result) {
        List<String> flags = new ArrayList<>();
        if (result.fallback()) {
            flags.add("FALLBACK_USED");
        }
        if ("undercover".equals(state.getGameId()) && StringUtils.hasText(result.content()) && StringUtils.hasText(actor.getWord())
                && result.content().contains(actor.getWord())) {
            flags.add("POSSIBLE_SECRET_LEAK");
        }
        if (StringUtils.hasText(result.content()) && repeatedSpeech(state, actor, result.content())) {
            flags.add("REPEATED_TEMPLATE");
        }
        if (StringUtils.hasText(result.targetPlayerId()) && !StringUtils.hasText(result.reason())) {
            flags.add("VOTE_WITHOUT_REASON");
        }
        if (StringUtils.hasText(result.targetPlayerId()) && state.getPlayers().stream().noneMatch(p -> p.isAlive() && p.getPlayerId().equals(result.targetPlayerId()))) {
            flags.add("ILLEGAL_TARGET");
        }
        if ("NIGHT_ACTION".equals(resolveAction(result)) && !StringUtils.hasText(result.action())) {
            flags.add("MISSING_NIGHT_ACTION");
        }
        return flags;
    }

    private boolean repeatedSpeech(GameState state, GamePlayerState actor, String content) {
        if (state.getLogs() == null) {
            return false;
        }
        String normalized = normalize(content);
        return state.getLogs().stream()
                .filter(log -> "speak".equals(log.getType()))
                .map(GameLogEntry::getMessage)
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .anyMatch(line -> line.contains(normalized));
    }

    private String resolveAction(AiDecisionResult result) {
        if (StringUtils.hasText(result.action()) || result.useHeal()) {
            return "NIGHT_ACTION";
        }
        if (StringUtils.hasText(result.targetPlayerId())) {
            return "VOTE";
        }
        return "SPEECH";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
