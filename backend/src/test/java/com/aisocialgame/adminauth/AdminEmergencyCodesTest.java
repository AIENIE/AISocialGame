package com.aisocialgame.adminauth;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.AdminAuthService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminEmergencyCodesTest extends EmergencyCodesContract {
    @Override DataSource database() { return new DriverManagerDataSource("jdbc:h2:mem:emergency" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); }
}

abstract class EmergencyCodesContract {
    AnnotationConfigApplicationContext context;
    AdminAuthService service;
    AdminAuthStore store;
    AdminAuthCrypto crypto;
    AppProperties properties;
    JdbcTemplate jdbc;
    final String subject = "configured-admin";
    String seed;
    abstract DataSource database();
    @Configuration @EnableTransactionManagement static class Tx {
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
    }
    @BeforeEach void setup() throws Exception {
        DataSource dataSource = database();
        jdbc = new JdbcTemplate(dataSource);
        executeSql("sql/20260810_admin_totp_auth.sql");
        executeSql("sql/20261004_admin_emergency_codes.sql");
        for (String table : List.of("admin_emergency_codes", "admin_emergency_challenge_versions", "admin_recovery_codes", "admin_auth_challenges", "admin_sessions", "admin_totp_credentials", "admin_operation_proofs", "admin_operation_challenges", "admin_auth_audit", "admin_auth_subject_locks")) jdbc.update("delete from " + table);
        properties = new AppProperties();
        properties.getAdmin().setUsername("admin");
        properties.getAdmin().setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("fixture-password"));
        properties.getAdmin().setTotpEncryptionKeys("v1:" + Base64.getEncoder().encodeToString(new byte[32]));
        properties.getAdmin().setTotpActiveKeyVersion("v1");
        context = new AnnotationConfigApplicationContext();
        context.register(Tx.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(AppProperties.class, () -> properties);
        context.registerBean(AdminAuthPolicy.class, () -> new AdminAuthPolicy("local", "password"));
        AdminRateLimiter limiter = mock(AdminRateLimiter.class);
        when(limiter.allow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        context.registerBean(AdminRateLimiter.class, () -> limiter);
        context.register(AdminAuthStore.class, AdminAuthCrypto.class, AdminAuthService.class);
        context.refresh();
        service = context.getBean(AdminAuthService.class);
        store = context.getBean(AdminAuthStore.class);
        crypto = context.getBean(AdminAuthCrypto.class);
        seed = crypto.randomBase32(20);
        store.insertCredential(subject, crypto.encrypt(seed), -1, Instant.now());
    }
    @AfterEach void close() { if (context != null) context.close(); }
    void executeSql(String file) throws Exception {
        String text = Files.readString(Path.of(file)).replaceAll("(?m)^--.*$", "");
        boolean h2;
        try (var connection = jdbc.getDataSource().getConnection()) { h2 = connection.getMetaData().getDatabaseProductName().equals("H2"); }
        if (h2) {
            text = text.replaceAll("(?m)^\\s*KEY .*\\n", "").replaceAll(",\\s*\\)", ")").replaceAll(" ENGINE=InnoDB[^;]*", "");
        }
        for (String statement : text.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }
    AdminAuthService.AdminPrincipal full() { return service.authenticate(service.login("admin", "fixture-password", "test").sessionToken()); }
    AdminAuthService.LoginResult recover(String raw) {
        return service.verifyRecovery(service.recoveryLogin("admin", "fixture-password", "test").challengeId(), raw, "test");
    }
    String otp(String secret) { return AdminTotp.code(secret, Instant.now().getEpochSecond() / 30); }

    AdminAuthService totpService() {
        AdminRateLimiter limiter = mock(AdminRateLimiter.class);
        when(limiter.allow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        return proxiedService(new AdminAuthPolicy("local","totp"),crypto,limiter);
    }
    AdminAuthService proxiedService(AdminAuthPolicy policy, AdminAuthCrypto encryption, AdminRateLimiter limiter) {
        var target = new AdminAuthService(properties,policy,store,encryption,limiter);
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(context.getBean(PlatformTransactionManager.class),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (AdminAuthService) proxy.getProxy();
    }
    @Test void initialEnrollmentDisplaysTenAndTotpGetRequiresNewCurrentCode() {
        jdbc.update("delete from admin_totp_credentials");
        var totp = totpService();
        var login=totp.login("admin","fixture-password","test");
        assertEquals("ENROLLMENT_REQUIRED",login.state());
        var setup=totp.startEnrollment(login.challengeId(),"test");
        var issued=totp.confirmEnrollment(login.challengeId(),otp(setup.manualKey()),"test");
        assertEquals(10,issued.recoveryCodes().size());
        var full=totp.authenticate(issued.sessionToken());
        assertThrows(ApiException.class, () -> totp.getRecoveryCodes(full,otp(setup.manualKey()),"test"));
        String fresh=AdminTotp.code(setup.manualKey(),Instant.now().getEpochSecond()/30+1);
        var get=totp.getRecoveryCodes(full,fresh,"test");
        assertEquals(0,get.generatedCount()); assertEquals(new HashSet<>(issued.recoveryCodes()),new HashSet<>(get.recoveryCodes()));
        assertThrows(ApiException.class, () -> totp.getRecoveryCodes(full,fresh,"test"));
    }
    @Test void successfulRebindRejectsOldTotpSessionsChallengesAndProofs() {
        var localFull=full(); var codes=service.getRecoveryCodes(localFull,"","test").recoveryCodes();
        var totp=totpService();
        var login=totp.login("admin","fixture-password","test");
        var issued=totp.verifyTotp(login.challengeId(),otp(seed),"test");
        var full=totp.authenticate(issued.sessionToken());
        var oldChallenge=totp.login("admin","fixture-password","test");
        var recoverLogin=totp.login("admin","fixture-password","test");
        var recovered=totp.verifyRecovery(recoverLogin.challengeId(),codes.getFirst(),"test");
        assertTrue(recovered.expiresAt().isBefore(Instant.now().plusSeconds(601)));
        var recovery=totp.authenticate(recovered.sessionToken()); var pending=totp.startRebind(recovery,"test");
        var rebound=totp.confirmRebind(recovery,pending.challengeId(),otp(pending.manualKey()),"test");
        assertNotNull(totp.authenticate(rebound.sessionToken()));
        assertThrows(ApiException.class, () -> totp.authenticate(issued.sessionToken()));
        assertThrows(ApiException.class, () -> totp.verifyTotp(oldChallenge.challengeId(),otp(seed),"test"));
        var newLogin=totp.login("admin","fixture-password","test");
        assertThrows(ApiException.class, () -> totp.verifyTotp(newLogin.challengeId(),AdminTotp.code(seed,Instant.now().getEpochSecond()/30+1),"test"));
        assertTrue(jdbc.queryForObject("select count(*) from admin_auth_challenges where consumed_at is null and challenge_hash=?",Integer.class,crypto.hashToken(oldChallenge.challengeId()))==0);
    }
    @Test void tenCodesRemainStableAndOnlyConsumedSlotIsReplenished() {
        var full = full();
        var first = service.getRecoveryCodes(full, "", "test");
        assertEquals(10, first.generatedCount()); assertEquals(10, first.remaining());
        assertEquals(10, new HashSet<>(first.recoveryCodes()).size());
        assertTrue(first.recoveryCodes().stream().allMatch(v -> v.matches("[A-F0-9]{8}(-[A-F0-9]{8}){3}")));
        assertEquals(new HashSet<>(first.recoveryCodes()), new HashSet<>(service.getRecoveryCodes(full, "", "test").recoveryCodes()));
        assertEquals(0, service.getRecoveryCodes(full, "", "test").generatedCount());
        String consumed = first.recoveryCodes().getFirst();
        recover(" " + consumed.toLowerCase().replace("-", " ") + " ");
        var filled = service.getRecoveryCodes(full, "", "test");
        assertEquals(1, filled.generatedCount()); assertFalse(filled.recoveryCodes().contains(consumed));
        assertTrue(filled.recoveryCodes().containsAll(first.recoveryCodes().subList(1,10)));
        assertThrows(ApiException.class, () -> recover(consumed));
    }
    @Test void recoveryKeepsOldSeedUntilConfirmAndDoesNotRefill() {
        var full = full(); var codes = service.getRecoveryCodes(full,"","test").recoveryCodes();
        var recovery = service.authenticate(recover(codes.getFirst()).sessionToken());
        assertThrows(ApiException.class, () -> service.getRecoveryCodes(recovery,"","test"));
        var pending = service.startRebind(recovery,"test");
        assertEquals(seed, crypto.decrypt(store.credential(subject).orElseThrow().encryptedSecret(),store.credential(subject).orElseThrow().nonce(),"v1"));
        assertThrows(ApiException.class, () -> service.confirmRebind(recovery,pending.challengeId(),"bad","test"));
        assertEquals(9, store.activeRecoveryCount(subject));
        var result = service.confirmRebind(recovery,pending.challengeId(),otp(pending.manualKey()),"test");
        assertTrue(result.recoveryCodes().isEmpty()); assertEquals(9,store.activeRecoveryCount(subject));
        assertThrows(ApiException.class, () -> service.getRecoveryCodes(full,"","test"));
        assertThrows(ApiException.class, () -> service.startRebind(recovery,"test"));
        var rebound = service.authenticate(result.sessionToken());
        var filled = service.getRecoveryCodes(rebound,"","test");
        assertEquals(1,filled.generatedCount()); assertTrue(filled.recoveryCodes().containsAll(codes.subList(1,10)));
        assertNotNull(recover(codes.get(1)).sessionToken());
    }
    @Test void expiredAuthorizationAndChallengeCannotReplaceCredential() {
        var full = full(); var codes = service.getRecoveryCodes(full,"","test").recoveryCodes();
        var recovery = service.authenticate(recover(codes.getFirst()).sessionToken());
        var pending = service.startRebind(recovery,"test");
        jdbc.update("update admin_auth_challenges set expires_at=? where challenge_hash=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),crypto.hashToken(pending.challengeId()));
        assertThrows(ApiException.class, () -> service.confirmRebind(recovery,pending.challengeId(),otp(pending.manualKey()),"test"));
        assertEquals(1,store.credential(subject).orElseThrow().credentialVersion());
        jdbc.update("update admin_sessions set expires_at=? where session_hash=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)),recovery.sessionHash());
        assertThrows(ApiException.class, () -> service.startRebind(recovery,"test"));
        assertEquals(9,store.activeRecoveryCount(subject));
    }
    @Test void challengeBoundToRecoverySessionAndMaximumFiveAttempts() {
        var full = full(); var codes=service.getRecoveryCodes(full,"","test").recoveryCodes();
        var a=service.authenticate(recover(codes.get(0)).sessionToken()); var b=service.authenticate(recover(codes.get(1)).sessionToken());
        var pending=service.startRebind(a,"test");
        assertThrows(ApiException.class, () -> service.confirmRebind(b,pending.challengeId(),otp(pending.manualKey()),"test"));
        for(int i=0;i<4;i++) assertThrows(ApiException.class, () -> service.confirmRebind(a,pending.challengeId(),"bad","test"));
        assertThrows(ApiException.class, () -> service.confirmRebind(a,pending.challengeId(),otp(pending.manualKey()),"test"));
        assertEquals(1,store.credential(subject).orElseThrow().credentialVersion());
    }
    @Test void noBindingAndWrongPasswordAreRejected() {
        assertThrows(ApiException.class, () -> service.recoveryLogin("admin","wrong","test"));
        var full=full(); jdbc.update("delete from admin_totp_credentials");
        assertThrows(ApiException.class, () -> service.getRecoveryCodes(full,"","test"));
    }
    @Test void ciphertextIsBoundToBackendSubjectAndRecordAndSurvivesRotationRestart() {
        var full=full(); var codes=service.getRecoveryCodes(full,"","test").recoveryCodes();
        var record=store.activeRecoveryCodes(subject).getFirst();
        assertThrows(IllegalStateException.class, () -> crypto.decryptRecovery("other",record.recordId(),record.ciphertext(),record.nonce(),record.keyVersion()));
        assertThrows(IllegalStateException.class, () -> crypto.decryptRecovery(subject,"other",record.ciphertext(),record.nonce(),record.keyVersion()));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(record.ciphertext(),record.nonce(),record.keyVersion()));
        properties.getAdmin().setTotpEncryptionKeys("v1:"+Base64.getEncoder().encodeToString(new byte[32])+",v2:"+Base64.getEncoder().encodeToString(new byte[32])); properties.getAdmin().setTotpActiveKeyVersion("v2");
        var rotated=new AdminAuthCrypto(properties);
        AdminRateLimiter limiter = mock(AdminRateLimiter.class); when(limiter.allow(anyString(),anyInt(),any(Duration.class))).thenReturn(true);
        var changed = proxiedService(new AdminAuthPolicy("local","password"),rotated,limiter);
        assertEquals(new HashSet<>(codes),new HashSet<>(changed.getRecoveryCodes(full,"","test").recoveryCodes()));
        assertTrue(store.activeRecoveryCodes(subject).stream().allMatch(recordAfter -> recordAfter.keyVersion().equals("v2")));
        var totp = proxiedService(new AdminAuthPolicy("local","totp"),rotated,limiter);
        var login = totp.login("admin","fixture-password","test"); totp.verifyTotp(login.challengeId(),otp(seed),"test");
        var credentialAfter = store.credential(subject).orElseThrow();
        assertEquals("v2",credentialAfter.keyVersion()); assertEquals(seed,rotated.decrypt(credentialAfter.encryptedSecret(),credentialAfter.nonce(),credentialAfter.keyVersion()));
        var restarted=new AdminAuthCrypto(properties);
        assertEquals(new HashSet<>(codes), new HashSet<>(store.activeRecoveryCodes(subject).stream().map(v -> restarted.decryptRecovery(subject,v.recordId(),v.ciphertext(),v.nonce(),v.keyVersion())).toList()));
    }
    @Test void migrationRerunRetainsNewCodesAndFullSession() throws Exception {
        var full=full(); var codes=service.getRecoveryCodes(full,"","test").recoveryCodes();
        jdbc.update("insert into admin_recovery_codes(subject_id,code_hash,created_at) values(?,?,?)",subject,crypto.hashRecoveryCode("LEGACY"),java.sql.Timestamp.from(Instant.now()));
        var recovery=service.authenticate(recover(codes.getFirst()).sessionToken());
        var pending=service.startRebind(recovery,"test");
        jdbc.update("update admin_emergency_upgrade set completed_at=null where version_id=1");
        executeSql("sql/20261004_admin_emergency_codes.sql");
        assertThrows(ApiException.class, () -> recover("LEGACY"));
        assertThrows(ApiException.class, () -> service.startRebind(recovery,"test"));
        assertTrue(store.challenge(crypto.hashToken(pending.challengeId())).orElseThrow().consumedAt()!=null);
        executeSql("sql/20261004_admin_emergency_codes.sql");
        var filled=service.getRecoveryCodes(full,"","test");
        assertTrue(filled.recoveryCodes().containsAll(codes.subList(1,10)));
        assertEquals(1,filled.generatedCount());
        executeSql("sql/20261004_admin_emergency_codes.sql");
        assertEquals(0,service.getRecoveryCodes(full,"","test").generatedCount());
    }
    @Test void concurrentConsumptionSucceedsExactlyOnce() throws Exception {
        var full=full(); String raw=service.getRecoveryCodes(full,"","test").recoveryCodes().getFirst();
        String c1=service.recoveryLogin("admin","fixture-password","test").challengeId(), c2=service.recoveryLogin("admin","fixture-password","test").challengeId();
        List<Boolean> results=race(() -> tryRecovery(c1,raw), () -> tryRecovery(c2,raw));
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count()); assertEquals(9,store.activeRecoveryCount(subject));
    }
    @Test void concurrentGetsNeverExceedTenOrChangeRetainedValues() throws Exception {
        var full=full(); var result=race(() -> service.getRecoveryCodes(full,"","test"), () -> service.getRecoveryCodes(full,"","test"));
        assertEquals(10,store.activeRecoveryCount(subject)); assertEquals(new HashSet<>(result.get(0).recoveryCodes()),new HashSet<>(result.get(1).recoveryCodes()));
        assertEquals(10,result.stream().mapToInt(AdminAuthService.RecoveryCodesResult::generatedCount).sum());
    }
    @Test void getAndRebindRaceRetainsUnusedCodesAndRevokesOldSession() throws Exception {
        var full=full(); var codes=service.getRecoveryCodes(full,"","test").recoveryCodes();
        var recovery=service.authenticate(recover(codes.getFirst()).sessionToken()); var pending=service.startRebind(recovery,"test");
        race(() -> {try {service.getRecoveryCodes(full,"","test");}catch(ApiException expected){}return true;}, () -> {service.confirmRebind(recovery,pending.challengeId(),otp(pending.manualKey()),"test");return true;});
        assertTrue(store.activeRecoveryCount(subject)>=9 && store.activeRecoveryCount(subject)<=10);
        assertThrows(ApiException.class, () -> service.getRecoveryCodes(full,"","test"));
        assertNotNull(recover(codes.get(1)).sessionToken());
    }
    boolean tryRecovery(String challenge,String raw) { try {service.verifyRecovery(challenge,raw,"test");return true;}catch(ApiException expected){return false;} }
    <T> List<T> race(Callable<T> a,Callable<T> b) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) { CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1); List<Future<T>> fs=new ArrayList<>();
            for(var call:List.of(a,b)) fs.add(executor.submit(() -> {ready.countDown();start.await();return call.call();}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();return List.of(fs.get(0).get(60,TimeUnit.SECONDS),fs.get(1).get(60,TimeUnit.SECONDS)); }
    }
}
