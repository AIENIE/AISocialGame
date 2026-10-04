package com.aisocialgame.service;

import com.aisocialgame.adminauth.AdminAuthCrypto;
import com.aisocialgame.adminauth.AdminAuthPolicy;
import com.aisocialgame.adminauth.AdminAuthStore;
import com.aisocialgame.adminauth.AdminRateLimiter;
import com.aisocialgame.adminauth.AdminTotp;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminAuthService {
    public static final String COOKIE_NAME = "AISOCIAL_ADMIN_SESSION";
    public static final String AUTHORITY = "AUTH_LOCAL_ADMIN";
    public static final String RECOVERY_SCOPE = "RECOVERY_REBIND_ONLY";
    public static final String PRINCIPAL_ATTRIBUTE = AdminAuthService.class.getName() + ".principal";
    private static final String SUBJECT = "configured-admin";
    private static final Duration CHALLENGE_TTL = Duration.ofSeconds(120);
    private static final Duration PROOF_TTL = Duration.ofSeconds(60);

    private final AppProperties properties;
    private final AdminAuthPolicy policy;
    private final AdminAuthStore store;
    private final AdminAuthCrypto crypto;
    private final AdminRateLimiter limiter;

    public AdminAuthService(AppProperties properties, AdminAuthPolicy policy, AdminAuthStore store,
                            AdminAuthCrypto crypto, AdminRateLimiter limiter) {
        this.properties = properties;
        this.policy = policy;
        this.store = store;
        this.crypto = crypto;
        this.limiter = limiter;
    }

    public PolicyResult policy() {
        return new PolicyResult(policy.environment(), policy.authMode(), policy.totpRequired(),
                store.credentialExists(SUBJECT) ? "ENROLLED" :
                        policy.totpRequired() ? "NOT_ENROLLED" : "NOT_APPLICABLE");
    }

    public boolean secureCookie() {
        return properties.getAdmin().isCookieSecure();
    }

    public LoginResult login(String username, String password, String source) {
        return passwordLogin(username, password, source, false);
    }

    public LoginResult recoveryLogin(String username, String password, String source) {
        return passwordLogin(username, password, source, true);
    }

    private LoginResult passwordLogin(String username, String password, String source, boolean recovery) {
        requireRate("login:user:" + normalizeUsername(username), 10, source, 20, Duration.ofMinutes(5));
        if (!properties.getAdmin().getUsername().equals(username)
                || !crypto.passwordMatches(password, properties.getAdmin().getPasswordHash())) {
            audit("admin.login.password", null, source, "FAILED", "INVALID_CREDENTIALS");
            throw authenticationFailed();
        }
        Instant passwordAt = Instant.now();
        if (policy.passwordOnly() && !recovery) {
            IssuedSession issued = issueSession("FULL", "PASSWORD", passwordAt, null, 0L);
            audit("admin.login.password", issued.sessionHash(), source, "SUCCESS", null);
            return LoginResult.authenticated(issued.token(), username, properties.getAdmin().getDisplayName(),
                    "FULL", issued.expiresAt(), List.of());
        }
        String next = store.credentialExists(SUBJECT) ? "TOTP_REQUIRED" : "ENROLLMENT_REQUIRED";
        String purpose = "TOTP_REQUIRED".equals(next) ? "LOGIN" : "ENROLLMENT";
        String challenge = createChallenge(purpose, null, passwordAt, null);
        audit("admin.login.password", null, source, "SUCCESS", next);
        return LoginResult.challenge(next, challenge, Instant.now().plus(CHALLENGE_TTL));
    }

    @Transactional
    public EnrollmentStart startEnrollment(String challengeId, String source) {
        requireRate("enrollment:start", 5, source, 10, Duration.ofMinutes(10));
        store.lockSubject(SUBJECT);
        ChallengeData challenge = requireChallenge(challengeId, "ENROLLMENT", null);
        if (store.credentialExists(SUBJECT)) {
            throw authenticationFailed();
        }
        String secret;
        if (challenge.challenge().encryptedSecret() == null) {
            secret = crypto.randomBase32(20);
            if (!store.setPendingSecret(challenge.hash(), crypto.encrypt(secret))) {
                challenge = requireChallenge(challengeId, "ENROLLMENT", null);
                secret = decryptPending(challenge.challenge());
            }
        } else {
            secret = decryptPending(challenge.challenge());
        }
        String issuer = "AISocialGame Admin";
        String label = properties.getAdmin().getUsername() + "@AISocialGame";
        String uri = "otpauth://totp/" + encode(issuer + ":" + label) + "?secret=" + secret
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=6&period=30";
        audit("admin.enrollment.start", null, source, "SUCCESS", null);
        return new EnrollmentStart(challengeId, uri, secret, challenge.challenge().expiresAt());
    }

    @Transactional
    public LoginResult confirmEnrollment(String challengeId, String code, String source) {
        requireRate("enrollment:verify", 10, source, 20, Duration.ofMinutes(5));
        attempt(challengeId);
        store.lockSubject(SUBJECT);
        ChallengeData data = requireChallenge(challengeId, "ENROLLMENT", null);
        if (store.credentialLocked(SUBJECT).isPresent()) {
            throw authenticationFailed();
        }
        String secret = decryptPending(data.challenge());
        long timestep = AdminTotp.matchingTimestep(secret, code, Instant.now().getEpochSecond(), -1);
        if (timestep < 0 || !store.consumeChallenge(data.hash())) {
            audit("admin.enrollment.confirm", null, source, "FAILED", "INVALID_TOTP");
            throw authenticationFailed();
        }
        store.insertCredential(SUBJECT, crypto.encrypt(secret), timestep, Instant.now());
        List<String> recoveryCodes = fillRecoveryCodes().recoveryCodes();
        IssuedSession issued = issueSession("FULL", "PASSWORD_TOTP", data.challenge().passwordAt(),
                Instant.now(), 1L);
        audit("admin.enrollment.confirm", issued.sessionHash(), source, "SUCCESS", null);
        return LoginResult.authenticated(issued.token(), properties.getAdmin().getUsername(),
                properties.getAdmin().getDisplayName(), "FULL", issued.expiresAt(), recoveryCodes);
    }

    @Transactional
    public LoginResult verifyTotp(String challengeId, String code, String source) {
        requireRate("login:totp", 10, source, 20, Duration.ofMinutes(5));
        attempt(challengeId);
        store.lockSubject(SUBJECT);
        ChallengeData data = requireChallenge(challengeId, "LOGIN", null);
        AdminAuthStore.Credential credential = store.credentialLocked(SUBJECT).orElseThrow(this::authenticationFailed);
        String secret = crypto.decrypt(credential.encryptedSecret(), credential.nonce(), credential.keyVersion());
        long timestep = AdminTotp.matchingTimestep(secret, code, Instant.now().getEpochSecond(),
                credential.lastAcceptedTimestep() == null ? -1 : credential.lastAcceptedTimestep());
        if (timestep < 0 || !store.acceptTimestep(SUBJECT, timestep) || !store.consumeChallenge(data.hash())) {
            audit("admin.login.totp", null, source, "FAILED", "INVALID_OR_REPLAYED_TOTP");
            throw authenticationFailed();
        }
        rotateKeyIfNeeded(credential, secret);
        IssuedSession issued = issueSession("FULL", "PASSWORD_TOTP", data.challenge().passwordAt(),
                Instant.now(), credential.credentialVersion());
        audit("admin.login.totp", issued.sessionHash(), source, "SUCCESS", null);
        return LoginResult.authenticated(issued.token(), properties.getAdmin().getUsername(),
                properties.getAdmin().getDisplayName(), "FULL", issued.expiresAt(), List.of());
    }

    @Transactional
    public LoginResult verifyRecovery(String challengeId, String recoveryCode, String source) {
        requireRate("login:recovery", 5, source, 10, Duration.ofMinutes(10));
        attempt(challengeId);
        store.lockSubject(SUBJECT);
        ChallengeData data = requireChallenge(challengeId, "LOGIN", null);
        String matchingHash = store.activeRecoveryCodes(SUBJECT).stream().map(AdminAuthStore.RecoveryCode::hash)
                .filter(hash -> crypto.recoveryCodeMatches(recoveryCode, hash))
                .findFirst().orElseThrow(this::authenticationFailed);
        AdminAuthStore.Credential credential = store.credentialLocked(SUBJECT).orElseThrow(this::authenticationFailed);
        if (!store.consumeRecoveryHash(SUBJECT, matchingHash) || !store.consumeChallenge(data.hash())) {
            throw authenticationFailed();
        }
        IssuedSession issued = issueSession(RECOVERY_SCOPE, "PASSWORD_RECOVERY", data.challenge().passwordAt(),
                null, credential.credentialVersion());
        audit("admin.login.recovery", issued.sessionHash(), source, "SUCCESS", "RESTRICTED_REBIND_ONLY");
        return LoginResult.authenticated(issued.token(), properties.getAdmin().getUsername(),
                properties.getAdmin().getDisplayName(), RECOVERY_SCOPE, issued.expiresAt(), List.of());
    }

    @Transactional
    public EnrollmentStart startRebind(AdminPrincipal principal, String source) {
        requireRecovery(principal);
        store.lockSubject(SUBJECT);
        revalidate(principal);
        requireRate("rebind:start:session:" + principal.sessionHash(), 3, source, 6, Duration.ofMinutes(10));
        String secret = crypto.randomBase32(20);
        String challenge = createChallenge("REBIND", principal.sessionHash(), principal.passwordAt(), crypto.encrypt(secret));
        String issuer = "AISocialGame Admin";
        String uri = "otpauth://totp/" + encode(issuer + ":" + properties.getAdmin().getUsername())
                + "?secret=" + secret + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=6&period=30";
        audit("admin.rebind.start", principal.sessionHash(), source, "SUCCESS", null);
        return new EnrollmentStart(challenge, uri, secret, Instant.now().plus(CHALLENGE_TTL));
    }

    @Transactional
    public LoginResult confirmRebind(AdminPrincipal principal, String challengeId, String code, String source) {
        requireRecovery(principal);
        requireRate("rebind:verify:session:" + principal.sessionHash(), 5, source, 10, Duration.ofMinutes(10));
        attempt(challengeId);
        store.lockSubject(SUBJECT);
        revalidate(principal);
        ChallengeData data = requireChallenge(challengeId, "REBIND", principal.sessionHash());
        String secret = decryptPending(data.challenge());
        long timestep = AdminTotp.matchingTimestep(secret, code, Instant.now().getEpochSecond(), -1);
        if (timestep < 0 || !store.consumeChallenge(data.hash())) {
            throw authenticationFailed();
        }
        long version = store.replaceCredential(SUBJECT, crypto.encrypt(secret), timestep, Instant.now());
        store.revokeAuthenticationState(SUBJECT);
        List<String> recoveryCodes = List.of();
        IssuedSession issued = issueSession("FULL", "PASSWORD_TOTP", principal.passwordAt(), Instant.now(), version);
        audit("admin.rebind.confirm", issued.sessionHash(), source, "SUCCESS", null);
        return LoginResult.authenticated(issued.token(), properties.getAdmin().getUsername(),
                properties.getAdmin().getDisplayName(), "FULL", issued.expiresAt(), recoveryCodes);
    }

    @Transactional
    public RecoveryCodesResult getRecoveryCodes(AdminPrincipal principal, String code, String source) {
        requireFull(principal);
        requireRate("recovery:get:session:" + principal.sessionHash(), 5, source, 10, Duration.ofMinutes(10));
        store.lockSubject(SUBJECT);
        revalidate(principal);
        if (store.credentialLocked(SUBJECT).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "请先完成动态码绑定");
        }
        if (policy.totpRequired()) verifyFreshTotp(code);
        RecoveryCodesResult result = fillRecoveryCodes();
        audit("admin.recovery.get", principal.sessionHash(), source, "SUCCESS", null);
        return result;
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(AdminPrincipal principal, String code, String source) {
        return getRecoveryCodes(principal, code, source).recoveryCodes();
    }

    public AdminPrincipal authenticate(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期");
        }
        String hash = crypto.hashToken(rawSessionToken);
        AdminAuthStore.Session session = store.activeSession(hash, policy, passwordCredentialHash(),
                        properties.getAdmin().getSessionIdleMinutes(),
                        properties.getAdmin().getRecoverySessionIdleMinutes())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期"));
        return new AdminPrincipal(properties.getAdmin().getUsername(), hash, session.scope(), AUTHORITY,
                policy.authMode(), session.passwordAt(), session.totpAt(), session.expiresAt());
    }

    public AdminPrincipal authenticate(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return authenticate(cookie.getValue());
                }
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期");
    }

    public void logout(AdminPrincipal principal, String source) {
        store.revokeSession(principal.sessionHash());
        audit("admin.logout", principal.sessionHash(), source, "SUCCESS", null);
    }

    public MeResult me(AdminPrincipal principal) {
        return new MeResult(principal.username(), properties.getAdmin().getDisplayName(), principal.scope(),
                policy.authMode(), store.activeRecoveryCount(SUBJECT), principal.expiresAt());
    }

    public OperationChallenge createOperationChallenge(AdminPrincipal principal, String action, String target,
                                                       String source) {
        requireFull(principal);
        requireRate("proof:mint:session:" + principal.sessionHash(), 5, source, 10, Duration.ofMinutes(1));
        String raw = crypto.randomToken();
        Instant expiresAt = Instant.now().plus(CHALLENGE_TTL);
        store.insertOperationChallenge(crypto.hashToken(raw), SUBJECT, principal.sessionHash(), action, target, expiresAt);
        audit("admin.operation.challenge", principal.sessionHash(), source, "SUCCESS", action);
        return new OperationChallenge(raw, expiresAt);
    }

    @Transactional
    public OperationProof verifyOperation(AdminPrincipal principal, String challengeId, String code, String source) {
        requireFull(principal);
        requireRate("proof:verify:session:" + principal.sessionHash(), 10, source, 20, Duration.ofMinutes(1));
        String challengeHash = crypto.hashToken(challengeId == null ? "" : challengeId);
        AdminAuthStore.OperationChallenge challenge = store.operationChallenge(challengeHash)
                .filter(value -> value.consumedAt() == null && value.expiresAt().isAfter(Instant.now())
                        && value.subject().equals(SUBJECT) && value.sessionHash().equals(principal.sessionHash()))
                .orElseThrow(this::authenticationFailed);
        if (!store.incrementOperationAttempt(challengeHash)) {
            throw authenticationFailed();
        }
        store.lockSubject(SUBJECT);
        revalidate(principal);
        verifyFreshTotp(code);
        if (!store.consumeOperationChallenge(challengeHash)) {
            throw authenticationFailed();
        }
        String proof = crypto.randomToken();
        Instant expiresAt = Instant.now().plus(PROOF_TTL);
        store.insertProof(crypto.hashToken(proof), challenge, expiresAt);
        audit("admin.operation.verify", principal.sessionHash(), source, "SUCCESS", challenge.action());
        return new OperationProof(proof, expiresAt, challenge.action(), challenge.target());
    }

    @Transactional
    public boolean consumeOperationProof(AdminPrincipal principal, String rawProof, String action, String target) {
        if (rawProof == null) {
            return false;
        }
        String hash = crypto.hashToken(rawProof);
        AdminAuthStore.OperationProof proof = store.operationProofForUpdate(hash).orElse(null);
        if (proof == null || proof.consumedAt() != null || !proof.expiresAt().isAfter(Instant.now())
                || !SUBJECT.equals(proof.subject()) || !principal.sessionHash().equals(proof.sessionHash())
                || !action.equals(proof.action()) || !target.equals(proof.target())) {
            return false;
        }
        return store.consumeProofHash(hash);
    }

    @Scheduled(fixedDelayString = "${app.admin.auth-cleanup-interval-ms:3600000}",
            initialDelayString = "${app.admin.auth-cleanup-initial-delay-ms:3600000}")
    public void cleanupExpiredState() {
        store.cleanupExpiredAuthenticationState(Instant.now().minus(Duration.ofDays(1)));
    }

    public AdminPrincipal currentPrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof AdminPrincipal principal && AUTHORITY.equals(principal.authority())) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "管理员登录已过期");
    }

    private String createChallenge(String purpose, String sessionHash, Instant passwordAt,
                                   AdminAuthCrypto.EncryptedValue secret) {
        String raw = crypto.randomToken();
        store.insertChallenge(crypto.hashToken(raw), SUBJECT, purpose, sessionHash, passwordAt, secret,
                Instant.now().plus(CHALLENGE_TTL));
        return raw;
    }

    private ChallengeData requireChallenge(String raw, String purpose, String sessionHash) {
        if (raw == null || raw.isBlank()) {
            throw authenticationFailed();
        }
        String hash = crypto.hashToken(raw);
        AdminAuthStore.Challenge challenge = store.challengeLocked(hash)
                .filter(value -> value.consumedAt() == null && value.expiresAt().isAfter(Instant.now())
                        && value.attempts() <= 5 && SUBJECT.equals(value.subject()) && purpose.equals(value.purpose())
                        && (sessionHash == null || sessionHash.equals(value.sessionHash())))
                .orElseThrow(this::authenticationFailed);
        long current = store.credentialLocked(SUBJECT).map(AdminAuthStore.Credential::credentialVersion).orElse(0L);
        if (challenge.credentialVersion() != current) throw authenticationFailed();
        return new ChallengeData(hash, challenge);
    }

    private IssuedSession issueSession(String scope, String assurance, Instant passwordAt, Instant totpAt,
                                       long credentialVersion) {
        String token = crypto.randomToken();
        String hash = crypto.hashToken(token);
        Instant now = Instant.now();
        int lifetimeMinutes = Math.max(1, RECOVERY_SCOPE.equals(scope)
                ? Math.min(10, properties.getAdmin().getRecoverySessionMinutes()) : properties.getAdmin().getSessionMinutes());
        int idleMinutes = Math.max(1, RECOVERY_SCOPE.equals(scope)
                ? properties.getAdmin().getRecoverySessionIdleMinutes() : properties.getAdmin().getSessionIdleMinutes());
        Instant expiresAt = now.plusSeconds(lifetimeMinutes * 60L);
        store.insertSession(hash, SUBJECT, scope, policy, assurance, passwordAt, totpAt, credentialVersion,
                passwordCredentialHash(), now, expiresAt, now.plusSeconds(idleMinutes * 60L));
        return new IssuedSession(token, hash, expiresAt);
    }

    private void verifyFreshTotp(String code) {
        AdminAuthStore.Credential credential = store.credentialLocked(SUBJECT).orElseThrow(this::authenticationFailed);
        String secret = crypto.decrypt(credential.encryptedSecret(), credential.nonce(), credential.keyVersion());
        long timestep = AdminTotp.matchingTimestep(secret, code, Instant.now().getEpochSecond(),
                credential.lastAcceptedTimestep() == null ? -1 : credential.lastAcceptedTimestep());
        if (timestep < 0 || !store.acceptTimestep(SUBJECT, timestep)) {
            throw authenticationFailed();
        }
        rotateKeyIfNeeded(credential, secret);
    }

    private void rotateKeyIfNeeded(AdminAuthStore.Credential credential, String secret) {
        if (!crypto.activeKeyVersion().equals(credential.keyVersion())) {
            store.rotateCredentialKey(SUBJECT, crypto.encrypt(secret));
        }
    }

    private void requireFull(AdminPrincipal principal) {
        if (principal == null || !AUTHORITY.equals(principal.authority()) || !"FULL".equals(principal.scope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "恢复会话仅允许重绑动态验证器");
        }
    }

    private void requireRecovery(AdminPrincipal principal) {
        if (principal == null || !AUTHORITY.equals(principal.authority()) || !RECOVERY_SCOPE.equals(principal.scope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅恢复会话可执行重绑");
        }
    }

    private void requireRate(String subjectDimension, int subjectLimit, String source, int sourceLimit,
                             Duration window) {
        String normalizedSource = source == null || source.isBlank() ? "unknown" : source;
        String sourceDigest = crypto.hashToken(normalizedSource);
        int separator = subjectDimension.indexOf(':');
        String action = separator < 0 ? subjectDimension : subjectDimension.substring(0, separator);
        boolean userAllowed = limiter.allow(action + ":user:" + crypto.hashToken(SUBJECT), subjectLimit, window);
        boolean subjectAllowed = limiter.allow(subjectDimension, subjectLimit, window);
        boolean sourceAllowed = limiter.allow(action + ":source:" + sourceDigest, sourceLimit, window);
        boolean combinedAllowed = limiter.allow(action + ":combined:" + subjectDimension + ":" + sourceDigest,
                Math.min(subjectLimit, sourceLimit), window);
        if (!userAllowed || !subjectAllowed || !sourceAllowed || !combinedAllowed) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "管理员认证请求过于频繁",
                    "RATE_LIMITED", Map.of("retryAfterSeconds", window.toSeconds()));
        }
    }

    private RecoveryCodesResult fillRecoveryCodes() {
        List<AdminAuthStore.RecoveryCode> existing = store.activeRecoveryCodes(SUBJECT);
        if (existing.size() > 10) throw new IllegalStateException("Emergency code capacity exceeded");
        List<String> codes = new ArrayList<>();
        for (AdminAuthStore.RecoveryCode record : existing) {
            String raw = crypto.decryptRecovery(SUBJECT, record.recordId(), record.ciphertext(), record.nonce(), record.keyVersion());
            codes.add(raw);
            if (!crypto.activeKeyVersion().equals(record.keyVersion())) {
                store.rotateRecoveryCode(record.recordId(), crypto.encryptRecovery(SUBJECT, record.recordId(), raw));
            }
        }
        int generated = 10 - codes.size();
        for (int i = 0; i < generated; i++) {
            String raw = crypto.randomRecoveryCode();
            String id = java.util.UUID.randomUUID().toString();
            store.insertRecoveryCode(SUBJECT, id, crypto.hashRecoveryCode(raw), crypto.encryptRecovery(SUBJECT, id, raw));
            codes.add(raw);
        }
        return new RecoveryCodesResult(List.copyOf(codes), generated, codes.size());
    }

    private void attempt(String raw) {
        if (raw == null || !store.incrementChallengeAttempt(crypto.hashToken(raw))) throw authenticationFailed();
    }

    private void revalidate(AdminPrincipal principal) {
        AdminAuthStore.Session session = store.activeSessionLocked(principal.sessionHash(), policy, passwordCredentialHash(),
                properties.getAdmin().getSessionIdleMinutes(), properties.getAdmin().getRecoverySessionIdleMinutes()).orElseThrow(this::authenticationFailed);
        if (!session.scope().equals(principal.scope())) throw authenticationFailed();
        long current = store.credentialLocked(SUBJECT).map(AdminAuthStore.Credential::credentialVersion).orElse(0L);
        if (RECOVERY_SCOPE.equals(session.scope()) && session.credentialVersion() != current) throw authenticationFailed();
    }

    private String decryptPending(AdminAuthStore.Challenge challenge) {
        if (challenge.encryptedSecret() == null || challenge.nonce() == null || challenge.keyVersion() == null) {
            throw authenticationFailed();
        }
        return crypto.decrypt(challenge.encryptedSecret(), challenge.nonce(), challenge.keyVersion());
    }

    private void audit(String event, String sessionHash, String source, String result, String reason) {
        store.audit(event, SUBJECT, sessionHash, source, result, reason);
    }

    private String normalizeUsername(String value) {
        return value == null ? "missing" : crypto.hashToken(value.toLowerCase());
    }

    private String passwordCredentialHash() {
        return crypto.hashToken(properties.getAdmin().getPasswordHash());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ApiException authenticationFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "管理员认证失败", "ADMIN_AUTHENTICATION_FAILED", Map.of());
    }

    private record ChallengeData(String hash, AdminAuthStore.Challenge challenge) {
    }

    private record IssuedSession(String token, String sessionHash, Instant expiresAt) {
    }

    public record RecoveryCodesResult(List<String> recoveryCodes, int generatedCount, int remaining) {
        @Override public String toString() { return "RecoveryCodesResult[redacted]"; }
    }

    public record PolicyResult(String env, String authMode, boolean totpRequired, String enrollmentStatus) {
    }

    public record LoginResult(String state, String challengeId, Instant challengeExpiresAt, String sessionToken,
                              String username, String displayName, String sessionScope, Instant expiresAt,
                              List<String> recoveryCodes) {
        static LoginResult challenge(String state, String challenge, Instant expires) {
            return new LoginResult(state, challenge, expires, null, null, null, null, null, List.of());
        }

        static LoginResult authenticated(String token, String username, String displayName, String scope,
                                         Instant expiresAt, List<String> recoveryCodes) {
            return new LoginResult("AUTHENTICATED", null, null, token, username, displayName, scope,
                    expiresAt, recoveryCodes);
        }
    }

    public record EnrollmentStart(String challengeId, String otpauthUri, String manualKey, Instant expiresAt) {
    }

    public record AdminPrincipal(String username, String sessionHash, String scope, String authority, String authMode,
                                 Instant passwordAt, Instant totpAt, Instant expiresAt) {
    }

    public record MeResult(String username, String displayName, String sessionScope, String authMode,
                           int recoveryCodesRemaining, Instant expiresAt) {
    }

    public record OperationChallenge(String challengeId, Instant expiresAt) {
    }

    public record OperationProof(String proofToken, Instant expiresAt, String action, String target) {
    }
}
