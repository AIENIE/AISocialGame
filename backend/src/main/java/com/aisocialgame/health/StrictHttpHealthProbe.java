package com.aisocialgame.health;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Dependency-free, fail-closed probe for the backend health contract. */
public final class StrictHttpHealthProbe {
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictHttpHealthProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !isHealthy(args[0])) System.exit(1);
    }

    static boolean isHealthy(String endpoint) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())) return false;
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_BODY_BYTES) return false;
            try (InputStream input = connection.getInputStream()) {
                return isStrictUpBody(readBounded(input));
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean isStrictUpBody(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) return false;
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode document = JSON.readTree(json);
            JsonNode status = document == null || !document.isObject() ? null : document.get("status");
            return status != null && status.isTextual() && "UP".equals(status.textValue());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_BODY_BYTES) throw new IllegalStateException("oversized");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
