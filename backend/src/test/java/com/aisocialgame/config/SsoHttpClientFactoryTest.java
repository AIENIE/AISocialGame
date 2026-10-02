package com.aisocialgame.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SsoHttpClientFactoryTest {
    @TempDir Path temp;

    @Test void absentConfigurationKeepsSystemTrust() {
        assertNotNull(SsoHttpClientFactory.create("").sslContext());
    }

    @Test void malformedOrMissingTrustFailsClosed() throws Exception {
        Path empty = Files.createFile(temp.resolve("empty.pem"));
        assertThrows(IllegalStateException.class, () -> SsoHttpClientFactory.create(empty.toUri().toString()));
        assertThrows(IllegalStateException.class, () -> SsoHttpClientFactory.create(temp.resolve("missing.pem").toUri().toString()));
        Files.writeString(empty, "invalid certificate");
        assertThrows(IllegalStateException.class, () -> SsoHttpClientFactory.create(empty.toUri().toString()));
    }

    @Test void canonicalLocalCaLoadsWithoutDisablingHostnameChecks() {
        Path certificate = Path.of("../scripts/windows/local-trust/localcert-root-ca.crt");
        var client = SsoHttpClientFactory.create(certificate.toAbsolutePath().toUri().toString());
        assertNotNull(client.sslContext());
        assertEquals(java.net.http.HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals("HTTPS", client.sslParameters().getEndpointIdentificationAlgorithm());
    }
}
