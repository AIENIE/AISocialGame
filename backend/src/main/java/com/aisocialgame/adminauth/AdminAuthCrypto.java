package com.aisocialgame.adminauth;

import com.aisocialgame.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

@Component
public class AdminAuthCrypto {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String TOTP_AAD_PREFIX = "aisocialgame-admin-totp-v1|";
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final Map<String, SecretKeySpec> encryptionKeys;
    private final String activeKeyVersion;

    public AdminAuthCrypto(AppProperties properties) {
        this.encryptionKeys = parseKeys(properties.getAdmin().getTotpEncryptionKeys());
        this.activeKeyVersion = properties.getAdmin().getTotpActiveKeyVersion();
    }

    public boolean passwordMatches(String raw, String hash) {
        try {
            return raw != null && hash != null && bcrypt.matches(raw, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public String hashRecoveryCode(String raw) {
        return bcrypt.encode(normalizeRecovery(raw));
    }

    public boolean recoveryCodeMatches(String raw, String hash) {
        try {
            return bcrypt.matches(normalizeRecovery(raw), hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public EncryptedValue encrypt(String plaintext) {
        return encrypt(plaintext, TOTP_AAD_PREFIX);
    }

    public EncryptedValue encryptRecovery(String subject, String recordId, String plaintext) {
        return encrypt(plaintext, "aisocialgame-admin-emergency-v1|" + subject + "|" + recordId + "|");
    }

    public String decryptRecovery(String subject, String recordId, String ciphertext, byte[] nonce, String version) {
        return decrypt(ciphertext, nonce, version, "aisocialgame-admin-emergency-v1|" + subject + "|" + recordId + "|");
    }

    public String randomRecoveryCode() {
        String hex = java.util.HexFormat.of().withUpperCase().formatHex(randomBytes(16));
        return hex.substring(0, 8) + "-" + hex.substring(8, 16) + "-" + hex.substring(16, 24) + "-" + hex.substring(24);
    }

    private EncryptedValue encrypt(String plaintext, String aad) {
        SecretKeySpec key = encryptionKeys.get(activeKeyVersion);
        if (key == null) {
            throw new IllegalStateException("Active administrator TOTP encryption key is unavailable");
        }
        byte[] nonce = randomBytes(12);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((aad + activeKeyVersion).getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))),
                    nonce,
                    activeKeyVersion);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt administrator credential", ex);
        }
    }

    public String decrypt(String ciphertext, byte[] nonce, String keyVersion) {
        return decrypt(ciphertext, nonce, keyVersion, TOTP_AAD_PREFIX);
    }

    private String decrypt(String ciphertext, byte[] nonce, String keyVersion, String aad) {
        SecretKeySpec key = encryptionKeys.get(keyVersion);
        if (key == null || nonce == null || nonce.length != 12) {
            throw new IllegalStateException("Administrator TOTP key version is unavailable");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((aad + keyVersion).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt administrator credential", ex);
        }
    }

    public String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    public String randomBase32(int byteCount) {
        byte[] bytes = randomBytes(byteCount);
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(BASE32.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public String hashToken(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash administrator token", ex);
        }
    }

    public String activeKeyVersion() {
        return activeKeyVersion;
    }

    public boolean hasUsableActiveKey() {
        return activeKeyVersion != null && encryptionKeys.containsKey(activeKeyVersion);
    }

    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        random.nextBytes(value);
        return value;
    }

    private String normalizeRecovery(String value) {
        return value == null ? "" : value.replace("-", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private Map<String, SecretKeySpec> parseKeys(String raw) {
        Map<String, SecretKeySpec> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String entry : raw.split(",", -1)) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator != entry.lastIndexOf(':')) {
                throw new IllegalStateException("Invalid administrator TOTP encryption keyring");
            }
            String version = entry.substring(0, separator);
            String encoded = entry.substring(separator + 1);
            if (!version.matches("[A-Za-z0-9._-]{1,32}") || encoded.isEmpty()) {
                throw new IllegalStateException("Invalid administrator TOTP encryption keyring");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Administrator TOTP encryption keys must use Base64", ex);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException("Administrator TOTP encryption keys must be 32 bytes");
            }
            if (result.putIfAbsent(version, new SecretKeySpec(decoded, "AES")) != null) {
                throw new IllegalStateException("Administrator TOTP encryption key versions must be unique");
            }
        }
        return result;
    }

    public record EncryptedValue(String ciphertext, byte[] nonce, String keyVersion) {
        public EncryptedValue {
            nonce = nonce.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
