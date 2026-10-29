package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.RuleSupport;
import java.util.Map;

public final class LabCallContext implements AutoCloseable {
    public record Identity(String experimentId, String matchId, String purpose) {}
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private final Identity previous;
    private LabCallContext(Identity identity) { previous = CURRENT.get(); CURRENT.set(identity); }
    public static LabCallContext open(Map<String, Object> lab, String purpose) {
        return new LabCallContext(lab.isEmpty() ? null : new Identity(
                RuleSupport.text(lab.get("experimentId")), RuleSupport.text(lab.get("matchId")), purpose));
    }
    public static Identity current() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
