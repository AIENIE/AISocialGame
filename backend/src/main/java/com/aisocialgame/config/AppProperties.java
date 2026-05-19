package com.aisocialgame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
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
        private String userServiceBaseUrl = "https://userservice.seekerhut.com";
        private String callbackUrl = "https://aisocialgame.seekerhut.com/sso/callback";
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
        private String password = "";
        private String displayName = "系统管理员";
        private long tokenTtlHours = 8;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public long getTokenTtlHours() {
            return tokenTtlHours;
        }

        public void setTokenTtlHours(long tokenTtlHours) {
            this.tokenTtlHours = tokenTtlHours;
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
        private String payserviceJwt = "";
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

        public String getPayserviceJwt() {
            return payserviceJwt;
        }

        public void setPayserviceJwt(String payserviceJwt) {
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
                "http://aisocialgame.seekerhut.com",
                "http://aisocialgame.seekerhut.com:11030",
                "https://aisocialgame.seekerhut.com",
                "http://aisocialgame.aienie.com",
                "http://aisocialgame.aienie.com:11030",
                "https://aisocialgame.aienie.com"
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
