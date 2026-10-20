package com.aisocialgame.controller;

import com.aisocialgame.dto.*;
import com.aisocialgame.dto.LocalAiDebugView.*;
import com.aisocialgame.model.User;
import com.aisocialgame.service.debug.LocalAiDebugService;
import com.aisocialgame.web.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games/{gameId}/rooms/{roomId}/debug/ai")
public class LocalAiDebugController {
    private final LocalAiDebugService debug;
    public LocalAiDebugController(LocalAiDebugService debug) { this.debug = debug; }
    @GetMapping("/instances")
    public PagedResponse<Instance> instances(@PathVariable String gameId, @PathVariable String roomId, @CurrentUser User user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return debug.instances(gameId, roomId, user.getId(), page, size);
    }
    @GetMapping("/summary")
    public Summary summary(@PathVariable String gameId, @PathVariable String roomId, @CurrentUser User user, @RequestParam String instanceId) {
        return debug.summary(gameId, roomId, user.getId(), instanceId);
    }
    @GetMapping("/turns")
    public PagedResponse<Turn> turns(@PathVariable String gameId, @PathVariable String roomId, @CurrentUser User user,
            @RequestParam String instanceId, @RequestParam(required = false) Integer round,
            @RequestParam(required = false) String phase, @RequestParam(required = false) String actorId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return debug.turns(gameId, roomId, user.getId(), instanceId, round, phase, actorId, status, page, size);
    }
    @GetMapping("/turns/{jobId}")
    public Detail detail(@PathVariable String gameId, @PathVariable String roomId, @PathVariable String jobId, @CurrentUser User user) {
        return debug.detail(gameId, roomId, user.getId(), jobId);
    }
}
