package com.aisocialgame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    public static final String CANONICAL_PROJECT_KEY = "aisocialgame";

    private String projectKey = "aisocialgame";
    private Sso sso = new Sso();
    private Ai ai = new Ai();
    private Credit credit = new Credit();
    private Admin admin = new Admin();
    private External external = new External();
    private DemoSeed demoSeed = new DemoSeed();
    private Cors cors = new Cors();

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public static String requireCanonicalProjectKey(String projectKey) {
        if (!CANONICAL_PROJECT_KEY.equals(projectKey)) {
            throw new IllegalArgumentException("External request project key is not canonical");
        }
        return projectKey;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Sso getSso() {
        return sso;
    }

    public void setSso(Sso sso) {
        this.sso = sso;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Credit getCredit() {
        return credit;
    }

    public void setCredit(Credit credit) {
        this.credit = credit;
    }

    public External getExternal() {
        return external;
    }

    public void setExternal(External external) {
        this.external = external;
    }

    public DemoSeed getDemoSeed() {
        return demoSeed;
    }

    public void setDemoSeed(DemoSeed demoSeed) {
        this.demoSeed = demoSeed;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public static class Ai {
        private String defaultModel = "";
        private long systemUserId = 0;

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public long getSystemUserId() {
            return systemUserId;
        }

        public void setSystemUserId(long systemUserId) {
            this.systemUserId = systemUserId;
        }
    }

    public static class Sso {
        private String userServiceBaseUrl = "https://localuserservice.testhut.top";
        private String callbackUrl = "https://localsocialgame.testhut.top/sso/callback";
        private String loginPath = "/sso/login";
        private String registerPath = "/register";

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
        }

        public String getUserServiceBaseUrl() {
            return userServiceBaseUrl;
        }

        public void setUserServiceBaseUrl(String userServiceBaseUrl) {
            this.userServiceBaseUrl = userServiceBaseUrl;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public String getRegisterPath() {
            return registerPath;
        }

        public void setRegisterPath(String registerPath) {
            this.registerPath = registerPath;
        }
    }

    public static class Admin {
        private String username = "admin";
        private String passwordHash = "";
        private String displayName = "系统管理员";
        private long operatorUserId = 1;
        private String totpEncryptionKeys = "";
        private String totpActiveKeyVersion = "v1";
        private int sessionMinutes = 120;
        private int sessionIdleMinutes = 30;
        private int recoverySessionMinutes = 10;
        private int recoverySessionIdleMinutes = 10;
        private boolean cookieSecure = true;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getTotpEncryptionKeys() {
            return totpEncryptionKeys;
        }

        public void setTotpEncryptionKeys(String totpEncryptionKeys) {
            this.totpEncryptionKeys = totpEncryptionKeys;
        }

        public String getTotpActiveKeyVersion() {
            return totpActiveKeyVersion;
        }

        public void setTotpActiveKeyVersion(String totpActiveKeyVersion) {
            this.totpActiveKeyVersion = totpActiveKeyVersion;
        }

        public int getSessionMinutes() {
            return sessionMinutes;
        }

        public void setSessionMinutes(int sessionMinutes) {
            this.sessionMinutes = sessionMinutes;
        }

        public int getSessionIdleMinutes() {
            return sessionIdleMinutes;
        }

        public void setSessionIdleMinutes(int sessionIdleMinutes) {
            this.sessionIdleMinutes = sessionIdleMinutes;
        }

        public int getRecoverySessionMinutes() {
            return recoverySessionMinutes;
        }

        public void setRecoverySessionMinutes(int recoverySessionMinutes) {
            this.recoverySessionMinutes = recoverySessionMinutes;
        }

        public int getRecoverySessionIdleMinutes() {
            return recoverySessionIdleMinutes;
        }

        public void setRecoverySessionIdleMinutes(int recoverySessionIdleMinutes) {
            this.recoverySessionIdleMinutes = recoverySessionIdleMinutes;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public long getOperatorUserId() {
            return operatorUserId;
        }

        public void setOperatorUserId(long operatorUserId) {
            this.operatorUserId = operatorUserId;
        }
    }

    public static class Credit {
        private long checkinGrantTokens = 20;
        private int tempExpiryDays = 30;
        private long exchangeDailyLimit = 2000;
        private int redeemFailureLimitPerDay = 10;

        public long getCheckinGrantTokens() {
            return checkinGrantTokens;
        }

        public void setCheckinGrantTokens(long checkinGrantTokens) {
            this.checkinGrantTokens = checkinGrantTokens;
        }

        public int getTempExpiryDays() {
            return tempExpiryDays;
        }

        public void setTempExpiryDays(int tempExpiryDays) {
            this.tempExpiryDays = tempExpiryDays;
        }

        public long getExchangeDailyLimit() {
            return exchangeDailyLimit;
        }

        public void setExchangeDailyLimit(long exchangeDailyLimit) {
            this.exchangeDailyLimit = exchangeDailyLimit;
        }

        public int getRedeemFailureLimitPerDay() {
            return redeemFailureLimitPerDay;
        }

        public void setRedeemFailureLimitPerDay(int redeemFailureLimitPerDay) {
            this.redeemFailureLimitPerDay = redeemFailureLimitPerDay;
        }
    }

    public static class External {
        private boolean grpcAuthRequired = true;
        private String userserviceInternalGrpcToken = "";
        private UserServiceJwt userserviceJwt = new UserServiceJwt();
        private String payserviceLegacyStaticToken = "";
        private PayServiceJwt payserviceJwt = new PayServiceJwt();
        private String aiserviceHmacCaller = "";
        private String aiserviceHmacSecret = "";

        public boolean isGrpcAuthRequired() {
            return grpcAuthRequired;
        }

        public void setGrpcAuthRequired(boolean grpcAuthRequired) {
            this.grpcAuthRequired = grpcAuthRequired;
        }

        public String getUserserviceInternalGrpcToken() {
            return userserviceInternalGrpcToken;
        }

        public void setUserserviceInternalGrpcToken(String userserviceInternalGrpcToken) {
            this.userserviceInternalGrpcToken = userserviceInternalGrpcToken;
        }

        public UserServiceJwt getUserserviceJwt() {
            return userserviceJwt;
        }

        public void setUserserviceJwt(UserServiceJwt userserviceJwt) {
            this.userserviceJwt = userserviceJwt;
        }

        public String getPayserviceLegacyStaticToken() {
            return payserviceLegacyStaticToken;
        }

        public void setPayserviceLegacyStaticToken(String payserviceLegacyStaticToken) {
            this.payserviceLegacyStaticToken = payserviceLegacyStaticToken;
        }

        public PayServiceJwt getPayserviceJwt() {
            return payserviceJwt;
        }

        public void setPayserviceJwt(PayServiceJwt payserviceJwt) {
            this.payserviceJwt = payserviceJwt;
        }

        public String getAiserviceHmacCaller() {
            return aiserviceHmacCaller;
        }

        public void setAiserviceHmacCaller(String aiserviceHmacCaller) {
            this.aiserviceHmacCaller = aiserviceHmacCaller;
        }

        public String getAiserviceHmacSecret() {
            return aiserviceHmacSecret;
        }

        public void setAiserviceHmacSecret(String aiserviceHmacSecret) {
            this.aiserviceHmacSecret = aiserviceHmacSecret;
        }
    }

    public static class PayServiceJwt {
        private String callerId = "aisocialgame";
        private String issuer = "aisocialgame";
        private String service = "aisocialgame";
        private String secret = "";
        private String audience = "aienie-payservice-grpc";
        private String role = "SERVICE";
        private long ttlSeconds = 300L;
        private String scopes = String.join(",", PayServiceJwtConfigurationValidator.REQUIRED_SCOPES);

        public String getCallerId() {
            return callerId;
        }

        public void setCallerId(String callerId) {
            this.callerId = callerId;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }
    }

    public static class UserServiceJwt {
        private String callerId = "aisocialgame";
        private String issuer = "aisocialgame";
        private String secret = "";
        private String audience = "aienie-userservice-grpc";
        private long ttlSeconds = 300L;
        private String scopes = "user.auth.session.read,user.directory.read,user.ban.read,user.ban.write";

        public String getCallerId() {
            return callerId;
        }

        public void setCallerId(String callerId) {
            this.callerId = callerId;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of(
                "http://localhost:5173",
                "http://localhost:4173",
                "http://localhost:11030",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:4173",
                "http://127.0.0.1:11030",
                "http://localhost",
                "https://localhost",
                "https://localsocialgame.testhut.top",
                "https://socialgame.testhut.top"
        );

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class DemoSeed {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
