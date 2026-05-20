package com.aisocialgame.dto.admin;

public record AdminSafetySummaryResponse(
        long openHighRiskEvents,
        long blockedLast24h,
        long costAnomaliesLast24h,
        long activeControls
) {
}
