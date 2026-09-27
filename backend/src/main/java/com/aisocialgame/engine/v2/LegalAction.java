package com.aisocialgame.engine.v2;

import java.util.List;

/** Server-owned capabilities. A client or model may only choose one of these actions. */
public record LegalAction(String type, String label, String nightAction, List<String> targets, int maxLength) {
    public LegalAction {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
    public static LegalAction text(String type, String label, int maxLength) {
        return new LegalAction(type, label, null, List.of(), maxLength);
    }
    public static LegalAction target(String type, String label, List<String> targets, int maxLength) {
        return new LegalAction(type, label, null, targets, maxLength);
    }
    public static LegalAction night(String action, String label, List<String> targets) {
        return new LegalAction("NIGHT_ACTION", label, action, targets, 120);
    }
    public static LegalAction simple(String type, String label) {
        return text(type, label, 0);
    }
}
