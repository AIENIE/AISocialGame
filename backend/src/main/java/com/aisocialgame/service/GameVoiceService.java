package com.aisocialgame.service;

import com.aisocialgame.config.RequestIdFilter;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiTranscriptionClient;
import com.aisocialgame.integration.grpc.client.AiTranscriptionClient.Transcript;
import com.aisocialgame.model.User;
import com.aisocialgame.service.v2.V2GameService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class GameVoiceService {
    private static final int SAMPLE_RATE = 16_000;
    private static final int WAV_HEADER_BYTES = 44;
    static final int MAX_WAV_BYTES = WAV_HEADER_BYTES + 30 * SAMPLE_RATE * Short.BYTES;
    private static final long MAX_TRANSCRIPTION_MILLIS = 40_000;

    private final V2GameService game;
    private final AiTranscriptionClient client;

    public GameVoiceService(V2GameService game, AiTranscriptionClient client) {
        this.game = game;
        this.client = client;
    }

    public Transcript transcribe(String gameId, String roomId, User user, String phaseToken, String actionKey,
                                 InputStream body, long contentLength) throws IOException {
        if (user == null || user.getExternalUserId() == null || user.getExternalUserId() <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (contentLength > MAX_WAV_BYTES) throw audioTooLarge();
        byte[] audio = body.readNBytes(MAX_WAV_BYTES + 1);
        if (audio.length > MAX_WAV_BYTES) throw audioTooLarge();
        validateWav(audio);

        var opportunity = game.requireVoiceOpportunity(gameId, roomId, user.getId(), phaseToken, actionKey);
        long deadlineMillis = opportunity.endsAt() == null ? MAX_TRANSCRIPTION_MILLIS
                : Math.min(MAX_TRANSCRIPTION_MILLIS, Duration.between(LocalDateTime.now(), opportunity.endsAt()).toMillis());
        if (deadlineMillis <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "本次行动时间已到", "PHASE_CHANGED", Map.of());
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var result = client.transcribe(user.getExternalUserId(), opportunity.archiveId(),
                requestId == null ? UUID.randomUUID().toString() : requestId, audio, deadlineMillis);
        game.requireVoiceOpportunity(gameId, roomId, user.getId(), phaseToken, actionKey);
        return result;
    }

    private static ApiException audioTooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "录音最多 30 秒", "VOICE_AUDIO_INVALID", Map.of());
    }

    private static void validateWav(byte[] audio) {
        int sampleBytes = audio.length - WAV_HEADER_BYTES;
        ByteBuffer wav = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        if (sampleBytes <= 0 || sampleBytes % Short.BYTES != 0
                || !tag(audio, 0, "RIFF") || wav.getInt(4) != audio.length - 8 || !tag(audio, 8, "WAVE")
                || !tag(audio, 12, "fmt ") || wav.getInt(16) != 16 || wav.getShort(20) != 1 || wav.getShort(22) != 1
                || wav.getInt(24) != SAMPLE_RATE || wav.getInt(28) != SAMPLE_RATE * Short.BYTES
                || wav.getShort(32) != Short.BYTES || wav.getShort(34) != 16
                || !tag(audio, 36, "data") || wav.getInt(40) != sampleBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "录音格式不符合要求", "VOICE_AUDIO_INVALID", Map.of());
        }
    }

    private static boolean tag(byte[] audio, int offset, String expected) {
        return expected.equals(new String(audio, offset, expected.length(), StandardCharsets.US_ASCII));
    }
}
