package com.aisocialgame.service;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AuthResponse;
import com.aisocialgame.dto.AuthUserView;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.client.UserGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.ExternalUserProfile;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.token.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Service
@Transactional
public class AuthService {
    private static final String EXTERNAL_PASSWORD_MARKER = "{external}";
    private static final Pattern SSO_STATE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

    private final UserRepository userRepository;
    private final TokenStore tokenStore;
    private final UserGrpcClient userGrpcClient;
    private final BillingGrpcClient billingGrpcClient;
    private final BalanceService balanceService;
    private final ProjectCreditService projectCreditService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final HttpClient localInsecureHttpClient;

    public AuthService(UserRepository userRepository,
                       TokenStore tokenStore,
                       UserGrpcClient userGrpcClient,
                       BillingGrpcClient billingGrpcClient,
                       BalanceService balanceService,
                       ProjectCreditService projectCreditService,
                       AppProperties appProperties,
                       ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
        this.userGrpcClient = userGrpcClient;
        this.billingGrpcClient = billingGrpcClient;
        this.balanceService = balanceService;
        this.projectCreditService = projectCreditService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.localInsecureHttpClient = buildLocalInsecureHttpClient();
    }

    public String buildSsoLoginRedirectUrl(String state) {
        String loginPath = normalizeSsoPath(appProperties.getSso().getLoginPath(), "/sso/login");
        return buildSsoRedirectUrl(loginPath, state);
    }

    public String buildSsoRegisterRedirectUrl(String state) {
        String registerPath = normalizeSsoPath(appProperties.getSso().getRegisterPath(), "/register");
        return buildSsoRedirectUrl(registerPath, state);
    }

    private String buildSsoRedirectUrl(String path, String state) {
        String normalizedState = normalizeAndValidateState(state);
        String serviceAddress = resolveUserServiceBaseUrl();
        String base = trimTrailingSlash(serviceAddress);
        String encodedRedirect = URLEncoder.encode(appProperties.getSso().getCallbackUrl(), StandardCharsets.UTF_8);
        String encodedState = URLEncoder.encode(normalizedState, StandardCharsets.UTF_8);
        return base + path + "?redirect=" + encodedRedirect + "&state=" + encodedState;
    }

    private String resolveUserServiceBaseUrl() {
        if (StringUtils.hasText(appProperties.getSso().getUserServiceBaseUrl())) {
            return appProperties.getSso().getUserServiceBaseUrl().trim();
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "用户服务地址未配置");
    }

    public AuthResponse ssoCallback(String code, String redirect) {
        SsoTokenResponse session = exchangeSsoCode(code, redirect);
        return ssoCallback(session.userId(), session.username(), session.sessionId(), session.accessToken());
    }

    public AuthResponse ssoCallback(long userId, String username, String sessionId, String accessToken) {
        if (userId <= 0 || !StringUtils.hasText(sessionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SSO 回调参数不完整");
        }
        ExternalUserProfile profile = userGrpcClient.validateSession(userId, sessionId);
        if (profile == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SSO 会话无效或已过期");
        }
        String initRequestId = appProperties.getProjectKey() + ":auth-init:" + userId;
        billingGrpcClient.ensureUserInitialized(initRequestId, appProperties.getProjectKey(), userId);
        projectCreditService.ensureAccountInitialized(userId);

        User localUser = upsertLocalUser(profile, username);
        localUser.setSessionId(sessionId.trim());
        localUser.setAccessToken(StringUtils.hasText(accessToken) ? accessToken.trim() : "");
        localUser = userRepository.save(localUser);

        String token = issueToken(localUser);
        return new AuthResponse(token, buildUserView(localUser));
    }

    private SsoTokenResponse exchangeSsoCode(String code, String redirect) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(redirect)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SSO 回调参数不完整");
        }
        URI endpoint = URI.create(trimTrailingSlash(resolveUserServiceBaseUrl()) + "/sso/token");
        String formBody = "code=" + form(code.trim()) + "&redirect=" + form(redirect.trim());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        try {
            HttpResponse<String> response = send(request, endpoint);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "SSO 授权码无效或已过期");
            }
            SsoTokenResponse payload = objectMapper.readValue(response.body(), SsoTokenResponse.class);
            if (payload.userId() == null || payload.userId() <= 0 || !StringUtils.hasText(payload.sessionId()) || !StringUtils.hasText(payload.accessToken())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "SSO 授权码响应无效");
            }
            return payload;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SSO 授权码交换失败");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SSO 授权码交换中断");
        }
    }

    private String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest request, URI endpoint) throws IOException, InterruptedException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            if (!allowsLocalInsecureTls(endpoint)) {
                throw ex;
            }
            return localInsecureHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private boolean allowsLocalInsecureTls(URI endpoint) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        return "https".equalsIgnoreCase(scheme)
                && host != null
                && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || host.endsWith(".localhut.com"));
    }

    private HttpClient buildLocalInsecureHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(context)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize local SSO TLS fallback", ex);
        }
    }

    private record SsoTokenResponse(
            String accessToken,
            Long userId,
            String username,
            String sessionId,
            Integer rememberDays,
            Long expiresIn
    ) {
    }

    public String issueToken(User user) {
        String token = UUID.randomUUID().toString();
        tokenStore.store(token, user.getId());
        return token;
    }

    public User authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String localUserId = tokenStore.getUserId(token);
        if (!StringUtils.hasText(localUserId)) {
            return null;
        }
        User localUser = userRepository.findById(localUserId).orElse(null);
        if (localUser == null || localUser.getExternalUserId() == null || !StringUtils.hasText(localUser.getSessionId())) {
            return null;
        }
        ExternalUserProfile profile = userGrpcClient.validateSession(localUser.getExternalUserId(), localUser.getSessionId());
        if (profile == null) {
            tokenStore.revoke(token);
            return null;
        }
        mergeExternalProfile(localUser, profile, localUser.getNickname());
        return userRepository.save(localUser);
    }

    public AuthUserView currentUserView(String token) {
        User user = authenticate(token);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return buildUserView(user);
    }

    private User upsertLocalUser(ExternalUserProfile profile, String defaultNickname) {
        User local = userRepository.findByExternalUserId(profile.userId())
                .orElseGet(() -> new User());
        mergeExternalProfile(local, profile, defaultNickname);
        return local;
    }

    private void mergeExternalProfile(User local, ExternalUserProfile profile, String defaultNickname) {
        if (!StringUtils.hasText(local.getId())) {
            local.setId(UUID.randomUUID().toString());
        }
        local.setExternalUserId(profile.userId());
        local.setUsername(profile.username());
        if (StringUtils.hasText(profile.email())) {
            local.setEmail(profile.email().trim().toLowerCase(Locale.ROOT));
        } else if (!StringUtils.hasText(local.getEmail())) {
            local.setEmail(profile.userId() + "@placeholder.local");
        }
        local.setAvatar(StringUtils.hasText(profile.avatarUrl()) ? profile.avatarUrl() : local.getAvatar());
        if (!StringUtils.hasText(local.getNickname())) {
            local.setNickname(StringUtils.hasText(defaultNickname) ? defaultNickname : fallbackNickname(profile.username()));
        }
        if (!StringUtils.hasText(local.getPassword())) {
            local.setPassword(EXTERNAL_PASSWORD_MARKER);
        }
        if (local.getLevel() <= 0) {
            local.setLevel(1);
        }
    }

    private AuthUserView buildUserView(User user) {
        BalanceSnapshot snapshot;
        try {
            snapshot = balanceService.getUserBalance(user);
        } catch (Exception ignored) {
            snapshot = BalanceSnapshot.empty();
        }
        return new AuthUserView(user, snapshot);
    }

    private String fallbackNickname(String username) {
        if (StringUtils.hasText(username)) {
            return username;
        }
        return "玩家" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String trimTrailingSlash(String url) {
        String normalized = url;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeSsoPath(String configuredPath, String defaultPath) {
        String normalized = StringUtils.hasText(configuredPath) ? configuredPath.trim() : defaultPath;
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private String normalizeAndValidateState(String state) {
        if (!StringUtils.hasText(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SSO state 不能为空");
        }
        String normalized = state.trim();
        if (!SSO_STATE_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SSO state 格式不合法");
        }
        return normalized;
    }
}
