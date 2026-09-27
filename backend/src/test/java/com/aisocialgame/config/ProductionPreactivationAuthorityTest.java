package com.aisocialgame.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.nio.file.FileSystem;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;

class ProductionPreactivationAuthorityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileSystem testFileSystem = Jimfs.newFileSystem(Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build());
    private final Path temporary = testFileSystem.getPath("/tmp");

    ProductionPreactivationAuthorityTest() throws Exception {
        Files.createDirectories(temporary);
    }

    @Test
    void acceptsExactCanonicalDoubleSignedAuthority() throws Exception {
        Fixture fixture = writeFixture("ai-social-game", "prod-products-68", "rollout-fixture");
        assertDoesNotThrow(() -> ProductionPreactivationAuthority.verifyAt(fixture.root(), false));
    }

    @Test
    void rejectsBadSignatureWrongKeyAndKeyDigest() throws Exception {
        Fixture badSignatureFixture = writeFixture("ai-social-game", "prod-products-68", "rollout-fixture");
        Path signature = badSignatureFixture.root().resolve("production-publication-receipt.json.sig");
        makeWritable(signature);
        byte[] bad = Files.readAllBytes(signature);
        bad[0] ^= 1;
        Files.write(signature, bad);
        makeReadOnly(signature);
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(badSignatureFixture.root(), false));

        Fixture wrongKeyFixture = writeFixture("ai-social-game", "prod-products-68", "rollout-fixture-2");
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path key = wrongKeyFixture.root().resolve("production-preactivation-authority-public-key.pem");
        makeWritable(key);
        Files.write(key, canonicalPem(wrong));
        makeReadOnly(key);
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(wrongKeyFixture.root(), false));
    }

    @Test
    void rejectsComponentTargetAndRolloutReplayEvenWhenResigned() throws Exception {
        Fixture component = writeFixture("metaverse", "prod-products-68", "rollout-fixture");
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(component.root(), false));

        Fixture target = writeFixture("ai-social-game", "prod-services-45", "rollout-fixture-2");
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(target.root(), false));

        Fixture replay = writeFixture("ai-social-game", "prod-products-68", "replayed-rollout");
        Path outerPath = replay.root().resolve("production-release-manifest-v4.json");
        ObjectNode outer = (ObjectNode) canonicalObject(Files.readAllBytes(outerPath));
        outer.put("rollout_id", "different-sealed-rollout");
        makeWritable(outerPath);
        byte[] outerRaw = ProductionPreactivationAuthority.canonical(outer);
        Files.write(outerPath, outerRaw);
        makeReadOnly(outerPath);
        // The publication receipt is deliberately not regenerated: both the outer digest and rollout
        // binding must reject replay before the service can start.
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(replay.root(), false));
    }

    @Test
    void rejectsMissingExtraWritableAndNonCanonicalFiles() throws Exception {
        Fixture fixture = writeFixture("ai-social-game", "prod-products-68", "rollout-fixture");
        Files.writeString(fixture.root().resolve("unexpected"), "forbidden", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(fixture.root(), false));

        Files.delete(fixture.root().resolve("unexpected"));
        Path receipt = fixture.root().resolve("production-preactivation-authority.json");
        makeWritable(receipt);
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(fixture.root(), false));

        makeReadOnly(receipt);
        Path key = fixture.root().resolve("production-publication-public-key.pem");
        Files.delete(key);
        assertThrows(IllegalStateException.class,
                () -> ProductionPreactivationAuthority.verifyAt(fixture.root(), false));
    }

    private Fixture writeFixture(String component, String targetProfile, String preactivationRollout)
            throws Exception {
        Path root = temporary.resolve("authority-" + preactivationRollout);
        Files.createDirectory(root);
        KeyPair publicationKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair preactivationKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String issued = Instant.parse("2026-08-22T00:00:00Z").toString();
        String releaseId = "release-fixture";
        String releaseVersion = "20260823001";
        String outerRollout = preactivationRollout;

        ObjectNode target = JSON.createObjectNode();
        target.put("snapshot_sha256", digest('4'));
        target.put("target_profile_id", "prod-products-68");
        target.put("target_profile_revision", 7);
        target.put("target_revision", digest('3'));
        ObjectNode outer = JSON.createObjectNode();
        outer.put("canonical_component_id", "ai-social-game");
        outer.put("component", "ai-social-game");
        outer.put("config_overlay_digest", digest('7'));
        outer.put("environment", "production");
        outer.put("image_bindings_digest", digest('5'));
        outer.put("image_closure_digest", digest('6'));
        outer.put("image_evidence_digest", digest('8'));
        outer.put("operation", "release");
        outer.put("production_secret_bindings_digest", digest('9'));
        outer.put("project_key", "ai-social-game");
        outer.put("release_id", releaseId);
        outer.put("release_version", releaseVersion);
        outer.put("rollout_id", outerRollout);
        outer.put("schema_version", "v4");
        outer.put("source_commit", "a".repeat(40));
        outer.set("target", target);
        byte[] outerRaw = ProductionPreactivationAuthority.canonical(outer);

        ObjectNode publication = JSON.createObjectNode();
        publication.put("artifact_sha256", digest('b'));
        publication.put("builder_installation_receipt_sha256", digest('c'));
        publication.put("canonical_component_id", "ai-social-game");
        publication.put("issued_at_utc", issued);
        publication.put("offline_cache_receipt_sha256", digest('d'));
        publication.put("outer_manifest_sha256", sha256(outerRaw));
        publication.put("production_build_receipt_sha256", digest('e'));
        publication.put("production_build_signature_sha256", digest('f'));
        publication.put("registry_publication_receipt_sha256", digest('1'));
        publication.put("release_id", releaseId);
        publication.put("release_version", releaseVersion);
        publication.put("runtime_image_bindings_sha256", digest('5'));
        publication.put("runtime_image_evidence_sha256", digest('8'));
        publication.put("schema_version", "aienie-production-publication-receipt-v1");
        publication.put("signing_key_sha256", rawKeySHA256(publicationKey));
        publication.put("source_commit", "a".repeat(40));
        byte[] publicationRaw = ProductionPreactivationAuthority.canonical(publication);
        byte[] publicationSignature = sign(publicationKey, publicationRaw);

        ObjectNode preactivation = JSON.createObjectNode();
        preactivation.put("action", "authorize-compose-start");
        preactivation.put("approval_scope_sha256", digest('2'));
        preactivation.put("artifact_sha256", digest('b'));
        preactivation.put("backup_closure_sha256", digest('a'));
        preactivation.put("backup_closure_signature_sha256", digest('b'));
        preactivation.put("candidate_root",
                "/srv/aienie-products/ai-social-game/.aienie-releases/" + releaseVersion);
        preactivation.put("canonical_component_id", component);
        preactivation.put("config_overlay_sha256", digest('7'));
        preactivation.put("execution_generation", 11);
        preactivation.put("image_bindings_sha256", digest('5'));
        preactivation.put("image_closure_sha256", digest('6'));
        preactivation.put("invocation_sha256", digest('c'));
        preactivation.put("issued_at_utc", issued);
        preactivation.put("journal_path",
                "/var/lib/aienie-production/product-preactivation/ai-social-game/" + preactivationRollout
                        + "/11/" + digest('c') + "/receipt.json");
        preactivation.put("outer_manifest_sha256", sha256(outerRaw));
        preactivation.put("preactivation_signing_key_sha256", rawKeySHA256(preactivationKey));
        preactivation.put("publication_receipt_sha256", sha256(publicationRaw));
        preactivation.put("publication_signature_sha256", sha256(publicationSignature));
        preactivation.put("publication_signing_key_sha256", rawKeySHA256(publicationKey));
        preactivation.put("release_id", releaseId);
        preactivation.put("release_version", releaseVersion);
        preactivation.put("result", "authorized");
        preactivation.put("rollout_id", preactivationRollout);
        preactivation.put("rollout_plan_sha256", digest('d'));
        preactivation.put("schema_version", "aienie-production-preactivation-authority-v1");
        preactivation.put("secret_bindings_sha256", digest('9'));
        preactivation.put("target_profile_id", targetProfile);
        preactivation.put("target_profile_revision", 7);
        preactivation.put("target_revision", digest('3'));
        preactivation.put("target_snapshot_sha256", digest('4'));
        byte[] preactivationRaw = ProductionPreactivationAuthority.canonical(preactivation);
        byte[] preactivationSignature = sign(preactivationKey, preactivationRaw);

        write(root.resolve("production-release-manifest-v4.json"), outerRaw);
        write(root.resolve("production-publication-receipt.json"), publicationRaw);
        write(root.resolve("production-publication-receipt.json.sig"), publicationSignature);
        write(root.resolve("production-publication-public-key.pem"), canonicalPem(publicationKey));
        write(root.resolve("production-preactivation-authority.json"), preactivationRaw);
        write(root.resolve("production-preactivation-authority.json.sig"), preactivationSignature);
        write(root.resolve("production-preactivation-authority-public-key.pem"), canonicalPem(preactivationKey));
        return new Fixture(root);
    }

    private static JsonNode canonicalObject(byte[] raw) throws Exception {
        return JSON.readTree(raw);
    }

    private static byte[] sign(KeyPair key, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(payload);
        return signature.sign();
    }

    private static byte[] canonicalPem(KeyPair key) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPublic().getEncoded());
        return ("-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String rawKeySHA256(KeyPair key) throws Exception {
        byte[] encoded = key.getPublic().getEncoded();
        return sha256(java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private static String sha256(byte[] value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void write(Path path, byte[] value) throws Exception {
        Files.write(path, value);
        makeReadOnly(path);
    }

    private static void makeWritable(Path path) throws Exception {
        setPermissions(path, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
    }

    private static void makeReadOnly(Path path) throws Exception {
        setPermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws Exception {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) throw new UnsupportedOperationException("POSIX permissions are unavailable for test filesystem");
        view.setPermissions(permissions);
    }

    private record Fixture(Path root) {
    }
}
