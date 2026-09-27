package com.aisocialgame.service.ai.v2;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent comparison-wide reservation journal. A reserved but interrupted request still counts. */
final class AiRealismComparisonLedger implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FileChannel channel;
    private final FileLock lock;
    private final int limit;
    private int consumed;

    AiRealismComparisonLedger(Path path, int limit) throws IOException {
        this.limit = limit;
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Another comparison owns the external budget journal");
            if (channel.size() > 1_000_000) throw new IOException("External budget journal exceeds the expected bounded size");
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(channel.size()));
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            String content = new String(bytes.array(), StandardCharsets.UTF_8);
            if (!content.isEmpty() && !content.endsWith("\n")) throw new IOException("Incomplete external budget reservation; reconcile it before rerunning");
            for (String line : content.lines().toList()) {
                Map<?, ?> reservation = JSON.readValue(line, Map.class);
                Object ordinal = reservation.get("comparisonAttempt");
                if (!(ordinal instanceof Number number) || number.intValue() != consumed + 1
                        || !"ATTEMPT_RESERVED".equals(reservation.get("event"))) {
                    throw new IOException("External budget reservation sequence is invalid; refusing to reset it");
                }
                consumed++;
            }
            if (consumed > limit) throw new IOException("External comparison budget already exceeds its ceiling");
            lock = acquired;
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release();
            channel.close();
            throw error;
        }
    }

    synchronized int reserve(String runDirectory, String scenarioId, String variant, String requestId) throws IOException {
        if (consumed >= limit) throw new IOException("Cumulative comparison call ceiling reached, including prior and interrupted runs");
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("event", "ATTEMPT_RESERVED"); reservation.put("comparisonAttempt", consumed + 1);
        reservation.put("runDirectory", runDirectory); reservation.put("scenarioId", scenarioId);
        reservation.put("variant", variant); reservation.put("requestId", requestId);
        reservation.put("reservedAt", Instant.now().toString());
        ByteBuffer bytes = ByteBuffer.wrap((JSON.writeValueAsString(reservation) + "\n").getBytes(StandardCharsets.UTF_8));
        channel.position(channel.size());
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
        return ++consumed;
    }

    synchronized int consumed() { return consumed; }

    /** Windows byte-range locks require readers to use the owning channel. */
    synchronized byte[] snapshot() throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        long position = 0;
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes, position);
            if (count < 0) throw new IOException("Incomplete locked journal read");
            position += count;
        }
        return bytes.array();
    }

    synchronized String sha256() throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshot()));
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
