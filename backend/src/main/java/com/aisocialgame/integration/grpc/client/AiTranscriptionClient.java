package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.auth.AiGrpcHmacClientInterceptor;
import com.google.protobuf.ByteString;
import fireflychat.ai.v1.AiGatewayServiceGrpc;
import fireflychat.ai.v1.TranscribeAudioRequest;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class AiTranscriptionClient {
    private static final Logger log = LoggerFactory.getLogger(AiTranscriptionClient.class);
    private static final String MODEL_KEY = "deepseek-asr";
    private final AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub;

    public AiTranscriptionClient(@Qualifier("aiTranscriptionGrpcChannel") ManagedChannel channel,
                                 AiGrpcHmacClientInterceptor auth) {
        stub = AiGatewayServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    }

    public Transcript transcribe(long userId, String archiveId, String requestId, byte[] audio, long deadlineMillis) {
        var request = TranscribeAudioRequest.newBuilder()
                .setUserId(userId)
                .setProjectKey(AppProperties.CANONICAL_PROJECT_KEY)
                .setModelKey(MODEL_KEY)
                .setAudio(ByteString.copyFrom(audio))
                .setRequestId(requestId)
                .setSessionId(archiveId)
                .build();
        try {
            var result = stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS).transcribeAudio(request);
            if (!requestId.equals(result.getRequestId()) || !MODEL_KEY.equals(result.getModelKey())
                    || result.getDurationMs() < 0 || result.getDurationMs() > 120_000) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "语音转写响应异常", "VOICE_UNAVAILABLE", Map.of());
            }
            return new Transcript(result.getText(), result.getDurationMs());
        } catch (StatusRuntimeException error) {
            var code = error.getStatus().getCode();
            log.warn("Voice transcription failed requestId={} grpcStatus={}", requestId, code);
            throw switch (code) {
                case INVALID_ARGUMENT -> new ApiException(HttpStatus.BAD_REQUEST,
                        "录音格式不符合要求", "VOICE_AUDIO_INVALID", Map.of());
                case RESOURCE_EXHAUSTED -> new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "语音转写繁忙", "VOICE_BUSY", Map.of());
                case DEADLINE_EXCEEDED -> new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                        "语音转写超时", "VOICE_TIMEOUT", Map.of());
                case PERMISSION_DENIED -> new ApiException(HttpStatus.FORBIDDEN,
                        "语音转写暂不可用", "VOICE_UNAVAILABLE", Map.of());
                case INTERNAL, DATA_LOSS -> new ApiException(HttpStatus.BAD_GATEWAY,
                        "语音转写响应异常", "VOICE_UNAVAILABLE", Map.of());
                default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "语音转写暂不可用", "VOICE_UNAVAILABLE", Map.of());
            };
        }
    }

    public record Transcript(String text, long durationMs) {}
}
