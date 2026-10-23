package com.aisocialgame.lab;

/** One window shared by automatic and administrator-requested experiment reviews. */
public final class LabCoachLimits {
    public static final int FIRST_TEXT_MS = 600_000;
    public static final int IDLE_MS = 120_000;
    public static final int TOTAL_SECONDS = 1200;
    public static final long LEASE_MS = 1_230_000;
    public static final int MAX_OUTPUT_TOKENS = 32768;
    private LabCoachLimits() {}
}
