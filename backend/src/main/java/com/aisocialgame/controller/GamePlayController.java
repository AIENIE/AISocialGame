package com.aisocialgame.controller;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.model.User;
import com.aisocialgame.service.v2.V2GameService;
import com.aisocialgame.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games/{gameId}/rooms/{roomId}")
public class GamePlayController {
    private final V2GameService gamePlayService;
    private final com.aisocialgame.service.GameLogQueryService logs;

    public GamePlayController(V2GameService gamePlayService, com.aisocialgame.service.GameLogQueryService logs) {
        this.gamePlayService = gamePlayService;
        this.logs = logs;
    }

    @GetMapping("/state")
    public ResponseEntity<GameStateResponse> state(@PathVariable String gameId,
                                                   @PathVariable String roomId,
                                                   @CurrentUser User user) {
        return ResponseEntity.ok(gamePlayService.state(gameId, roomId, user));
    }

    @GetMapping("/logs")
    public ResponseEntity<com.aisocialgame.dto.GameLogPage> logs(@PathVariable String gameId,
                                                                  @PathVariable String roomId,
                                                                  @RequestParam(required = false) Long before,
                                                                  @RequestParam(defaultValue = "100") int size,
                                                                  @CurrentUser User user) {
        return ResponseEntity.ok(logs.page(gameId, roomId, user == null ? null : user.getId(), before, size));
    }

    @PostMapping("/start")
    public ResponseEntity<GameStateResponse> start(@PathVariable String gameId,
                                                   @PathVariable String roomId,
                                                   @CurrentUser User user) {
        return ResponseEntity.ok(gamePlayService.start(gameId, roomId, user));
    }

    @PostMapping("/action")
    public ResponseEntity<GameStateResponse> action(@PathVariable String gameId,
                                                    @PathVariable String roomId,
                                                    @Valid @RequestBody PlayerAction action,
                                                    @CurrentUser User user) {
        return ResponseEntity.ok(gamePlayService.action(gameId, roomId, user, action));
    }
}
