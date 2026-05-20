package com.aisocialgame.dto.admin;

public class AdminDashboardSummaryResponse {
    private long localUsers;
    private long localRooms;
    private long localPosts;
    private long localGameStates;
    private int aiModels;
    private long openHighRiskSafetyEvents;
    private long safetyBlocksLast24h;
    private long safetyCostAnomaliesLast24h;
    private long activeSafetyControls;

    public AdminDashboardSummaryResponse(long localUsers, long localRooms, long localPosts, long localGameStates, int aiModels) {
        this(localUsers, localRooms, localPosts, localGameStates, aiModels, 0, 0, 0, 0);
    }

    public AdminDashboardSummaryResponse(long localUsers,
                                         long localRooms,
                                         long localPosts,
                                         long localGameStates,
                                         int aiModels,
                                         long openHighRiskSafetyEvents,
                                         long safetyBlocksLast24h,
                                         long safetyCostAnomaliesLast24h,
                                         long activeSafetyControls) {
        this.localUsers = localUsers;
        this.localRooms = localRooms;
        this.localPosts = localPosts;
        this.localGameStates = localGameStates;
        this.aiModels = aiModels;
        this.openHighRiskSafetyEvents = openHighRiskSafetyEvents;
        this.safetyBlocksLast24h = safetyBlocksLast24h;
        this.safetyCostAnomaliesLast24h = safetyCostAnomaliesLast24h;
        this.activeSafetyControls = activeSafetyControls;
    }

    public long getLocalUsers() {
        return localUsers;
    }

    public long getLocalRooms() {
        return localRooms;
    }

    public long getLocalPosts() {
        return localPosts;
    }

    public long getLocalGameStates() {
        return localGameStates;
    }

    public int getAiModels() {
        return aiModels;
    }

    public long getOpenHighRiskSafetyEvents() {
        return openHighRiskSafetyEvents;
    }

    public long getSafetyBlocksLast24h() {
        return safetyBlocksLast24h;
    }

    public long getSafetyCostAnomaliesLast24h() {
        return safetyCostAnomaliesLast24h;
    }

    public long getActiveSafetyControls() {
        return activeSafetyControls;
    }
}
