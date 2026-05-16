package com.aisocialgame.controller;

import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.ReplayArchiveView;
import com.aisocialgame.dto.ReplayDetailResponse;
import com.aisocialgame.service.ReplayArchiveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/replays")
public class ReplayController {
    private final ReplayArchiveService replayArchiveService;

    public ReplayController(ReplayArchiveService replayArchiveService) {
        this.replayArchiveService = replayArchiveService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ReplayArchiveView>> list(@RequestParam(required = false) String gameId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(replayArchiveService.list(gameId, page, size));
    }

    @GetMapping("/my")
    public ResponseEntity<PagedResponse<ReplayArchiveView>> my(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(replayArchiveService.list(null, page, size));
    }

    @GetMapping("/{archiveId}")
    public ResponseEntity<ReplayArchiveView> detail(@PathVariable String archiveId) {
        return ResponseEntity.ok(replayArchiveService.detail(archiveId));
    }

    @GetMapping("/{archiveId}/events")
    public ResponseEntity<ReplayDetailResponse> events(@PathVariable String archiveId,
                                                       @RequestParam(defaultValue = "PUBLIC") String viewMode,
                                                       @RequestParam(required = false) String viewerPlayerId) {
        return ResponseEntity.ok(replayArchiveService.events(archiveId, viewMode, viewerPlayerId));
    }
}
