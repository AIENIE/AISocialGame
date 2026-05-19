package com.aisocialgame.controller;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.service.GamePlayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games/{gameId}/rooms/{roomId}")
public class GamePlayController {
    private final GamePlayService gamePlayService;
    private final AuthService authService;

    public GamePlayController(GamePlayService gamePlayService, AuthService authService) {
        this.gamePlayService = gamePlayService;
        this.authService = authService;
    }

    @GetMapping("/state")
    public ResponseEntity<GameStateResponse> state(@PathVariable String gameId,
                                                   @PathVariable String roomId,
                                                   @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.state(gameId, roomId, requireUser(token)));
    }

    @PostMapping("/start")
    public ResponseEntity<GameStateResponse> start(@PathVariable String gameId,
                                                   @PathVariable String roomId,
                                                   @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.start(gameId, roomId, requireUser(token)));
    }

    @PostMapping("/speak")
    public ResponseEntity<GameStateResponse> speak(@PathVariable String gameId,
                                                   @PathVariable String roomId,
                                                   @Valid @RequestBody SpeakRequest request,
                                                   @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.speak(gameId, roomId, request, requireUser(token)));
    }

    @PostMapping("/vote")
    public ResponseEntity<GameStateResponse> vote(@PathVariable String gameId,
                                                  @PathVariable String roomId,
                                                  @Valid @RequestBody VoteRequest request,
                                                  @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.vote(gameId, roomId, request, requireUser(token)));
    }

    @PostMapping("/night-action")
    public ResponseEntity<GameStateResponse> nightAction(@PathVariable String gameId,
                                                         @PathVariable String roomId,
                                                         @Valid @RequestBody NightActionRequest request,
                                                         @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.nightAction(gameId, roomId, request, requireUser(token)));
    }

    @PostMapping("/action")
    public ResponseEntity<GameStateResponse> action(@PathVariable String gameId,
                                                    @PathVariable String roomId,
                                                    @RequestBody PlayerAction action,
                                                    @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(gamePlayService.action(gameId, roomId, action, requireUser(token)));
    }

    private User requireUser(String token) {
        User user = authService.authenticate(token);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
