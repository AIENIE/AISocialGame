package com.aisocialgame.controller;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiTranscriptionClient.Transcript;
import com.aisocialgame.model.User;
import com.aisocialgame.service.GameVoiceService;
import com.aisocialgame.service.v2.V2GameService;
import com.aisocialgame.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/games/{gameId}/rooms/{roomId}")
public class GamePlayController {
    private final V2GameService gamePlayService;
    private final com.aisocialgame.service.GameLogQueryService logs;
    private final GameVoiceService voice;

    public GamePlayController(V2GameService gamePlayService, com.aisocialgame.service.GameLogQueryService logs,
                              GameVoiceService voice) {
        this.gamePlayService = gamePlayService;
        this.logs = logs;
        this.voice = voice;
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

    @PostMapping("/transcriptions")
    public ResponseEntity<Transcript> transcribe(@PathVariable String gameId,
                                                @PathVariable String roomId,
                                                @CurrentUser User user,
                                                @RequestHeader("X-Expected-Phase-Token") String expectedPhaseToken,
                                                @RequestHeader("X-Game-Action") String actionKey,
                                                HttpServletRequest request) throws IOException {
        if (!"audio/wav".equalsIgnoreCase(request.getContentType())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请上传 WAV 录音", "VOICE_AUDIO_INVALID", Map.of());
        }
        var transcript = voice.transcribe(gameId, roomId, user, expectedPhaseToken, actionKey,
                request.getInputStream(), request.getContentLengthLong());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transcript);
    }
}
