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
    public ResponseEntity<PagedResponse<ReplayArchiveView>> list(@com.aisocialgame.web.CurrentUser com.aisocialgame.model.User user, @RequestParam(required = false) String gameId,
                                                                 @RequestParam(required = false) String playerId,
                                                                 @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime from,
                                                                 @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime to,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(replayArchiveService.search(gameId, playerId, from, to, page, size, user.getId()));
    }

    @GetMapping("/my")
    public ResponseEntity<PagedResponse<ReplayArchiveView>> my(@com.aisocialgame.web.CurrentUser com.aisocialgame.model.User user,
                                                               @RequestParam(required = false) String gameId,
                                                               @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime from,
                                                               @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime to,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(replayArchiveService.search(gameId, user.getId(), from, to, page, size, user.getId()));
    }

    @GetMapping("/{archiveId}")
    public ResponseEntity<ReplayArchiveView> detail(@PathVariable String archiveId, @com.aisocialgame.web.CurrentUser com.aisocialgame.model.User user) {
        return ResponseEntity.ok(replayArchiveService.detail(archiveId, user.getId()));
    }

    @GetMapping("/{archiveId}/events")
    public ResponseEntity<ReplayDetailResponse> events(@PathVariable String archiveId,
                                                       @RequestParam(defaultValue = "PUBLIC") String viewMode,
                                                       @RequestParam(required = false) String viewerPlayerId,
                                                       @com.aisocialgame.web.CurrentUser(required = false) com.aisocialgame.model.User user) {
        return ResponseEntity.ok(replayArchiveService.authorizedEvents(archiveId, viewMode, viewerPlayerId, user == null ? null : user.getId()));
    }
}
