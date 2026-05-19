package com.aisocialgame.controller;

import com.aisocialgame.dto.AiChatRequest;
import com.aisocialgame.dto.AiChatResponse;
import com.aisocialgame.dto.AiChatStreamEvent;
import com.aisocialgame.dto.AiEmbeddingsRequest;
import com.aisocialgame.dto.AiEmbeddingsResponse;
import com.aisocialgame.dto.AiModelView;
import com.aisocialgame.dto.AiOcrRequest;
import com.aisocialgame.dto.AiOcrResponse;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AiProxyService;
import com.aisocialgame.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiProxyService aiProxyService;
    private final AuthService authService;

    public AiController(AiProxyService aiProxyService, AuthService authService) {
        this.aiProxyService = aiProxyService;
        this.authService = authService;
    }

    @GetMapping("/models")
    public ResponseEntity<List<AiModelView>> listModels() {
        return ResponseEntity.ok(aiProxyService.listModels().stream().map(AiModelView::new).toList());
    }

    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request,
                                               @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        return ResponseEntity.ok(new AiChatResponse(aiProxyService.chat(request, user)));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request,
                                 @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                AiChatResponse response = new AiChatResponse(aiProxyService.chat(request, user));
                String content = response.getContent() == null ? "" : response.getContent();
                int chunkSize = 2;
                for (int i = 0; i < content.length(); i += chunkSize) {
                    String chunk = content.substring(i, Math.min(i + chunkSize, content.length()));
                    emitter.send(SseEmitter.event().data(new AiChatStreamEvent(chunk, false)));
                    Thread.sleep(30L);
                }
                emitter.send(SseEmitter.event().data(AiChatStreamEvent.done(response)));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @PostMapping("/embeddings")
    public ResponseEntity<AiEmbeddingsResponse> embeddings(@Valid @RequestBody AiEmbeddingsRequest request,
                                                           @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        return ResponseEntity.ok(new AiEmbeddingsResponse(aiProxyService.embeddings(request, user)));
    }

    @PostMapping("/ocr")
    public ResponseEntity<AiOcrResponse> ocr(@RequestBody AiOcrRequest request,
                                             @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        return ResponseEntity.ok(new AiOcrResponse(aiProxyService.ocrParse(request, user)));
    }

    private User requireUser(String token) {
        User user = authService.authenticate(token);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return user;
    }
}
