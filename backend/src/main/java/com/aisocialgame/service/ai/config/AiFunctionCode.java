package com.aisocialgame.service.ai.config;

import java.util.Locale;

public enum AiFunctionCode {
    UNDERCOVER("谁是卧底"), WEREWOLF("狼人杀"), TURTLE_SOUP_PLAYER("海龟汤玩家"), TURTLE_SOUP_HOST("海龟汤主持人");
    public final String label;
    AiFunctionCode(String label) { this.label = label; }
    public static AiFunctionCode forGame(String gameId, String actorId) {
        return switch (gameId.toLowerCase(Locale.ROOT)) {
            case "undercover" -> UNDERCOVER;
            case "werewolf" -> WEREWOLF;
            case "turtle_soup" -> com.aisocialgame.engine.v2.RuleSupport.HOST.equals(actorId) ? TURTLE_SOUP_HOST : TURTLE_SOUP_PLAYER;
            default -> null; // Unregistered plugins retain their existing environment default.
        };
    }
}
