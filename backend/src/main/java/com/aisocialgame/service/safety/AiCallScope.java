package com.aisocialgame.service.safety;

/** Metadata propagation within a single synchronous RPC; always restore on exit. */
public final class AiCallScope implements AutoCloseable {
    private static final ThreadLocal<AiSafetyContext> CURRENT = new ThreadLocal<>();
    private final AiSafetyContext previous;
    private AiCallScope(AiSafetyContext context) { previous = CURRENT.get(); CURRENT.set(context); }
    public static AiCallScope open(AiSafetyContext context) { return new AiCallScope(context); }
    public static AiSafetyContext context(long user, String model) {
        AiSafetyContext c = CURRENT.get();
        return c == null ? AiSafetyContext.source("AI_RPC").user(String.valueOf(user), null).model(model) : c.model(model);
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
