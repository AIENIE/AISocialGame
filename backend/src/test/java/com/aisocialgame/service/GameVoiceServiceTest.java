package com.aisocialgame.service;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiTranscriptionClient;
import com.aisocialgame.model.User;
import com.aisocialgame.service.v2.V2GameService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameVoiceServiceTest {
    private final V2GameService game = mock(V2GameService.class);
    private final AiTranscriptionClient client = mock(AiTranscriptionClient.class);
    private final GameVoiceService service = new GameVoiceService(game, client);
    private final User user = new User();

    GameVoiceServiceTest() {
        user.setId("player");
        user.setExternalUserId(7L);
    }

    @Test void rejectsOversizedOrMalformedAudioBeforeCallingTheGateway() {
        var tooLarge = assertThrows(ApiException.class, () -> transcribe(new byte[GameVoiceService.MAX_WAV_BYTES + 1]));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getStatus());
        var invalid = assertThrows(ApiException.class, () -> transcribe(new byte[46]));
        assertEquals("VOICE_AUDIO_INVALID", invalid.getCode());
        verifyNoInteractions(client);
    }

    @Test void returnsTheTranscriptAfterRevalidatingTheSameOpportunity() throws Exception {
        byte[] audio = pcmWav();
        when(game.requireVoiceOpportunity("turtle_soup", "room", "player", "phase", "DISCUSS:"))
                .thenReturn(new V2GameService.VoiceOpportunity("archive", null));
        var result = new AiTranscriptionClient.Transcript("一条新线索", 1);
        when(client.transcribe(eq(7L), eq("archive"), anyString(), aryEq(audio), eq(40_000L))).thenReturn(result);

        assertEquals(result, transcribe(audio));
        var ordered = inOrder(game, client);
        ordered.verify(game).requireVoiceOpportunity("turtle_soup", "room", "player", "phase", "DISCUSS:");
        ordered.verify(client).transcribe(eq(7L), eq("archive"), anyString(), aryEq(audio), eq(40_000L));
        ordered.verify(game).requireVoiceOpportunity("turtle_soup", "room", "player", "phase", "DISCUSS:");
    }

    @Test void dropsTheResultIfTheOpportunityExpiresDuringTranscription() {
        when(game.requireVoiceOpportunity("turtle_soup", "room", "player", "phase", "DISCUSS:"))
                .thenReturn(new V2GameService.VoiceOpportunity("archive", null))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "局面已更新", "PHASE_CHANGED", Map.of()));
        when(client.transcribe(anyLong(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new AiTranscriptionClient.Transcript("迟到的文字", 1));
        assertEquals("PHASE_CHANGED", assertThrows(ApiException.class, () -> transcribe(pcmWav())).getCode());
        verify(client, times(1)).transcribe(anyLong(), anyString(), anyString(), any(), anyLong());
    }

    private AiTranscriptionClient.Transcript transcribe(byte[] audio) throws Exception {
        return service.transcribe("turtle_soup", "room", user, "phase", "DISCUSS:",
                new ByteArrayInputStream(audio), -1);
    }

    private static byte[] pcmWav() {
        return ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) 1).array();
    }
}
