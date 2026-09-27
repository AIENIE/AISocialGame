package com.aisocialgame.service.ai.v2;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import com.aisocialgame.model.AiTurnJob;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiBuildIdentityTest {
    @Test void missingOrMalformedBuildIsUnknown() {
        assertEquals(Map.of(), AiBuildIdentity.parse(null));
        for (String value : new String[]{"{}", "not-json", "{\"buildId\":\"legacy\"}"})
            assertEquals(Map.of(), AiBuildIdentity.parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))));
    }
    @Test void onlyNonSecretBuildFieldsAreRetainedAndWrittenToNewJob() {
        String value="{\"buildId\":\""+"b".repeat(64)+"\",\"sourceFingerprint\":\""+"a".repeat(64)+"\",\"secret\":\"do-not-copy\"}";
        assertEquals(Map.of("buildId","b".repeat(64),"sourceFingerprint","a".repeat(64)),AiBuildIdentity.parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))));
        var job=new AiTurnJob();AiJobDiagnostics.initialize(job,Instant.now(),ZoneId.of("Asia/Shanghai"));
        AiBuildIdentity.current().forEach((key,id)->assertEquals(id,job.getDiagnostics().get(key)));
        assertTrue(job.getObservation().isEmpty());
    }
    @Test void ReadingLegacyTaskDoesNotBackfillCurrentIdentity() {
        var job=new AiTurnJob();job.setDiagnostics(Map.of("formatVersion",1));
        assertFalse(job.getDiagnostics().containsKey("buildId"));
        assertFalse(job.getDiagnostics().containsKey("sourceFingerprint"));
    }
}
