package com.aisocialgame.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;

/** Requires the exported LocalCert public root for Windows-local gRPC clients. */
final class LocalGrpcTrustPolicy {
    private static final String ROOT_THUMBPRINT = "ABB779409203615F6864BC73A32A983B91E9D081";

    private LocalGrpcTrustPolicy() {}

    static void require(String trust) {
        try {
            URI uri = URI.create(trust);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("not a file URI");
            }
            Path path = Path.of(uri).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || !path.getFileName().toString().equals("localcert-root-ca.crt")) {
                throw new IllegalArgumentException("not the local root file");
            }
            try (var input = Files.newInputStream(path)) {
                var certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
                String actual = HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
                if (!ROOT_THUMBPRINT.equals(actual)) {
                    throw new IllegalArgumentException("wrong certificate fingerprint");
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("local gRPC must use the exported LocalCert root", exception);
        }
    }
}
