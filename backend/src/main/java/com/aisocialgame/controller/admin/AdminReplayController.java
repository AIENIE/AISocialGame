package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.ReplayDetailResponse;
import com.aisocialgame.service.ReplayArchiveService;
import com.aisocialgame.web.CurrentAdmin;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/replays")
public class AdminReplayController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminReplayController.class);
    private final ReplayArchiveService replays;
    public AdminReplayController(ReplayArchiveService replays) { this.replays = replays; }
    @GetMapping("/{archiveId}/events")
    public ReplayDetailResponse events(@CurrentAdmin String operator, @PathVariable String archiveId) {
        String actor = com.aisocialgame.logging.SafeLogValue.fingerprint(operator);
        String target = archiveId.replaceAll("[^A-Za-z0-9_-]", "_");
        try {
            var result = replays.events(archiveId, "GOD", null);
            result.setAvailableViews(java.util.List.of("PUBLIC", "GOD"));
            log.info("Admin evidence read actorFingerprint={} archiveId={} result=SUCCESS", actor, target);
            return result;
        } catch (RuntimeException failure) {
            log.warn("Admin evidence read actorFingerprint={} archiveId={} result=FAILURE errorType={}", actor, target, failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
