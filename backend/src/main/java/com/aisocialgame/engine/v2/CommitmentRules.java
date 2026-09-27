package com.aisocialgame.engine.v2;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.model.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Server interpretation of a single ballot or role's night turn, independent of its outcome. */
public final class CommitmentRules {
    private CommitmentRules() {}
    public record Opportunity(String id, String actorId, int round, String phase, String skill) {
        public Map<String, Object> value() { return Map.of("id", id, "actorId", actorId, "round", round, "phase", phase, "skill", skill); }
    }
    public record Receipt(Opportunity opportunity, String kind, String targetPlayerId) {}
    public static Opportunity opportunity(GameState s, String actor, int round, String phase) {
        String skill = "NIGHT".equals(phase) ? player(s, actor).getRole() : "BALLOT";
        return new Opportunity(s.getData().get("archiveId") + ":" + round + ":" + phase + ":" + actor + ":" + skill, actor, round, phase, skill);
    }
    public static Receipt submitted(GameState s, String actor, PlayerAction action) {
        boolean vote = "undercover".equals(s.getGameId()) && Set.of("VOTING", "RUNOFF").contains(s.getPhase())
                || "werewolf".equals(s.getGameId()) && "DAY_VOTE".equals(s.getPhase());
        boolean night = "werewolf".equals(s.getGameId()) && "NIGHT".equals(s.getPhase());
        if (!vote && !night) return null;
        String kind = vote ? ("SKIP".equalsIgnoreCase(action.getType()) || action.isAbstain() ? "ABSTAIN" : "VOTE")
                : ("SKIP".equalsIgnoreCase(action.getType()) || "SKIP".equalsIgnoreCase(action.getNightAction()) || "WITCH_SAVE".equalsIgnoreCase(action.getNightAction()) && !action.isUseHeal() ? "NIGHT_SKIP" : text(action.getNightAction()).toUpperCase(Locale.ROOT));
        return new Receipt(opportunity(s, actor, s.getRoundNumber(), s.getPhase()), kind,
                Set.of("ABSTAIN", "NIGHT_SKIP").contains(kind) ? "" : text(action.getTargetPlayerId()));
    }
    public static Opportunity bind(GameState s, GameRuleSet rules, String actor, Map<String, Object> action, int round) {
        if (!Set.of("undercover", "werewolf").contains(s.getGameId()) || round < s.getRoundNumber() || round > s.getRoundNumber() + 1) return null;
        GamePlayerState p = player(s, actor); if (!p.isAlive() || "SETTLEMENT".equals(s.getPhase())) return null;
        String kind = text(action.get("kind")); String phase;
        if (Set.of("VOTE", "ABSTAIN").contains(kind)) {
            if ("werewolf".equals(s.getGameId()) && "RUNOFF".equals(action.get("ballot"))) return null;
            phase = "werewolf".equals(s.getGameId()) ? "DAY_VOTE" : "RUNOFF".equals(action.get("ballot")) ? "RUNOFF" : "VOTING";
            if ("werewolf".equals(s.getGameId()) && strings(data(s).get("revealedIdiots")).contains(actor)) return null;
        } else {
            if (!"werewolf".equals(s.getGameId()) || !allowed(p.getRole()).contains(kind)) return null;
            phase = "NIGHT";
        }
        Opportunity o = opportunity(s, actor, round, phase);
        if (expiredReason(s, rules, o.value(), action) != null) return null;
        return o;
    }
    private static Set<String> allowed(String role) {
        return switch (role) {
            case "WEREWOLF" -> Set.of("WOLF_KILL", "NIGHT_SKIP");
            case "SEER" -> Set.of("SEER_CHECK", "NIGHT_SKIP");
            case "GUARD" -> Set.of("GUARD_PROTECT", "NIGHT_SKIP");
            case "WITCH" -> Set.of("WITCH_SAVE", "WITCH_POISON", "NIGHT_SKIP");
            default -> Set.of();
        };
    }
    private static Map<String, Object> data(GameState s) {
        return "werewolf".equals(s.getGameId()) ? map(s.getData().get("werewolf")) : s.getData();
    }
    public static String expiredReason(GameState s, GameRuleSet rules, Map<String, Object> o, Map<String, Object> action) {
        String actor = text(o.get("actorId")); String phase = text(o.get("phase")); int round = number(o.get("round"), -1);
        if ("SETTLEMENT".equals(s.getPhase())) return "GAME_ENDED";
        GamePlayerState p = s.getPlayers().stream().filter(v -> v.getPlayerId().equals(actor)).findFirst().orElse(null);
        if (p == null || !p.isAlive()) return "ACTOR_DEAD";
        String target = text(action.get("targetPlayerId"));
        if (!target.isBlank() && s.getPlayers().stream().noneMatch(v -> v.getPlayerId().equals(target) && v.isAlive())) return "TARGET_UNAVAILABLE";
        Map<String, Object> d = data(s);
        if (!"NIGHT".equals(phase) && strings(d.get("revealedIdiots")).contains(actor)) return "VOTE_RIGHT_LOST";
        if (round < s.getRoundNumber()) return "OPPORTUNITY_ENDED";
        if (round > s.getRoundNumber()) return null;
        if ("werewolf".equals(s.getGameId()) && Set.of("DEATH_ACTION", "LAST_WORDS").contains(s.getPhase())) {
            if ("NIGHT".equals(phase) || "DAY_VOTE".equals(phase) && "NIGHT".equals(d.get("afterDeaths"))) return "OPPORTUNITY_ENDED";
        }
        if (rank(s.getGameId(), s.getPhase()) > rank(s.getGameId(), phase)) return "OPPORTUNITY_ENDED";
        if (!phase.equals(s.getPhase())) return null;
        if ("NIGHT".equals(phase)) {
            List<String> queue = strings(d.get("nightQueue")); int index = queue.indexOf(actor);
            if (index < 0 || index < number(d.get("nightIndex"), 0)) return "OPPORTUNITY_ENDED";
            if (!actor.equals(d.get("nightActor"))) return null;
        } else if (map(d.get("votes")).containsKey(actor)) return "OPPORTUNITY_ENDED";
        // Only check dynamic target/medicine restrictions when this exact opportunity is available.
        String kind = text(action.get("kind"));
        boolean available = rules.legalActions(s, actor).stream().anyMatch(a -> {
            boolean matches = switch (kind) {
                case "ABSTAIN", "NIGHT_SKIP" -> "SKIP".equals(a.type());
                case "VOTE" -> "VOTE".equals(a.type());
                default -> kind.equals(a.nightAction());
            };
            return matches && (target.isBlank() || a.targets().contains(target));
        });
        return available ? null : "ACTION_UNAVAILABLE";
    }
    private static int rank(String game, String phase) {
        if ("undercover".equals(game)) return switch (phase) {
            case "DESCRIPTION" -> 0; case "CHALLENGE", "RESPONSE" -> 1; case "VOTING" -> 2;
            case "TIE_DEFENSE" -> 3; case "RUNOFF" -> 4; default -> -1;
        };
        return switch (phase) {
            case "NIGHT" -> 0; case "DAY_DISCUSS", "DAY_INTERACTION" -> 1; case "DAY_VOTE" -> 2;
            default -> -1; // Death actions/last words are interruptions, not a new opportunity.
        };
    }
}
