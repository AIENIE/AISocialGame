package com.aisocialgame.dto;

import java.util.List;

public class ReplayDetailResponse {
    private final ReplayArchiveView archive;
    private final String viewMode;
    private final List<ReplayEventView> events;

    public ReplayDetailResponse(ReplayArchiveView archive, String viewMode, List<ReplayEventView> events) {
        this.archive = archive;
        this.viewMode = viewMode;
        this.events = events;
    }

    public ReplayArchiveView getArchive() { return archive; }
    public String getViewMode() { return viewMode; }
    public List<ReplayEventView> getEvents() { return events; }
}
