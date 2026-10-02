package com.aisocialgame.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Uses the configured SSO CA while retaining normal hostname verification. */
public final class SsoHttpClientFactory {
    private SsoHttpClientFactory() {}

    public static HttpClient create(String trustCertificate) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (trustCertificate == null || trustCertificate.isBlank()) return builder.build();
        try {
            var certificates = CertificateFactory.getInstance("X.509");
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (var input = Files.newInputStream(Path.of(URI.create(trustCertificate)))) {
                int index = 0;
                for (var certificate : certificates.generateCertificates(input)) {
                    store.setCertificateEntry("sso-ca-" + index++, certificate);
                }
                if (index == 0) throw new IllegalArgumentException("No SSO trust certificates");
            }
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            var parameters = new javax.net.ssl.SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return builder.sslContext(context).sslParameters(parameters).build();
        } catch (Exception error) {
            throw new IllegalStateException("Invalid SSO trust certificate configuration", error);
        }
    }
}
