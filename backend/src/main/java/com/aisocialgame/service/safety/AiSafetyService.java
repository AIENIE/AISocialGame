package com.aisocialgame.service.safety;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.AiSafetyControl;
import com.aisocialgame.model.AiSafetyEvent;
import com.aisocialgame.repository.AiSafetyControlRepository;
import com.aisocialgame.repository.AiSafetyEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Transactional
public class AiSafetyService {
    public static final String SOURCE_ROOM_CHAT = "ROOM_CHAT";
    public static final String SOURCE_COMMUNITY = "COMMUNITY";
    public static final String SOURCE_AI_CHAT_INPUT = "AI_CHAT_INPUT";
    public static final String SOURCE_AI_CHAT_OUTPUT = "AI_CHAT_OUTPUT";
    public static final String SOURCE_ADMIN_AI_TEST = "ADMIN_AI_TEST";
    public static final String SOURCE_AI_PLAYER = "AI_PLAYER";
    public static final String SOURCE_GAME_SPEECH = "GAME_SPEECH";

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_ACKED = "ACKED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";
    private static final String SEVERITY_LOW = "LOW";
    private static final String SAFE_REPLACEMENT = "内容已根据安全策略替换。";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{8,}\\d)(?!\\d)");

    private final AiSafetyEventRepository eventRepository;
    private final AiSafetyControlRepository controlRepository;

    public AiSafetyService(AiSafetyEventRepository eventRepository,
                           AiSafetyControlRepository controlRepository) {
        this.eventRepository = eventRepository;
        this.controlRepository = controlRepository;
    }

    public AiSafetyResult review(String content, AiSafetyContext context) {
        String normalized = content == null ? "" : content.trim();
        AiSafetyResult controlResult = controlResult(normalized, context);
        if (controlResult != null) {
            return controlResult;
        }
        RuleDecision decision = evaluateRules(normalized);
        if (AiSafetyAction.ALLOW.equals(decision.action())) {
            return new AiSafetyResult(AiSafetyAction.ALLOW, SEVERITY_LOW, "NONE", "", normalized, null);
        }
        AiSafetyEvent event = createEvent(normalized, context, decision);
        return new AiSafetyResult(decision.action(), decision.severity(), decision.category(), decision.reason(), decision.safeContent(), event);
    }

    public String requireAllowedInput(String content, AiSafetyContext context) {
        AiSafetyResult result = review(content, context);
        if (result.allowed()) {
            return content;
        }
        if (result.redacted()) {
            return result.safeContent();
        }
        throw safetyException(result);
    }

    public String safeOutput(String content, AiSafetyContext context) {
        AiSafetyResult result = review(content, context);
        if (result.allowed()) {
            return content == null ? "" : content;
        }
        return result.safeContent();
    }

    @Transactional(readOnly = true)
    public Page<AiSafetyEvent> searchEvents(String status,
                                            String severity,
                                            String source,
                                            String roomId,
                                            String userId,
                                            String personaId,
                                            String modelKey,
                                            int page,
                                            int size) {
        int effectiveSize = Math.min(Math.max(size, 1), 100);
        return eventRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(status)) predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            if (StringUtils.hasText(severity)) predicates.add(cb.equal(root.get("severity"), severity.trim().toUpperCase(Locale.ROOT)));
            if (StringUtils.hasText(source)) predicates.add(cb.equal(root.get("source"), source.trim().toUpperCase(Locale.ROOT)));
            if (StringUtils.hasText(roomId)) predicates.add(cb.equal(root.get("roomId"), roomId.trim()));
            if (StringUtils.hasText(userId)) predicates.add(cb.equal(root.get("userId"), userId.trim()));
            if (StringUtils.hasText(personaId)) predicates.add(cb.equal(root.get("personaId"), personaId.trim()));
            if (StringUtils.hasText(modelKey)) predicates.add(cb.equal(root.get("modelKey"), modelKey.trim()));
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(Math.max(page, 0), effectiveSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public Optional<AiSafetyEvent> findEvent(long id) {
        return eventRepository.findById(id);
    }

    public AiSafetyEvent acknowledge(long id, String operator) {
        AiSafetyEvent event = eventRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "安全事件不存在"));
        event.setStatus(STATUS_ACKED);
        event.setAcknowledgedBy(operator);
        event.setAcknowledgedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return eventRepository.save(event);
    }

    public AiSafetyEvent close(long id, String operator, String reason) {
        AiSafetyEvent event = eventRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "安全事件不存在"));
        event.setStatus(STATUS_CLOSED);
        event.setClosedBy(operator);
        event.setClosedAt(LocalDateTime.now());
        event.setCloseReason(trim(reason, 255));
        event.setUpdatedAt(LocalDateTime.now());
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AiSafetyControl> activeControls() {
        LocalDateTime now = LocalDateTime.now();
        return controlRepository.findByActiveTrueOrderByIdDesc().stream()
                .filter(control -> control.getExpiresAt() == null || control.getExpiresAt().isAfter(now))
                .toList();
    }

    public AiSafetyControl createControl(String scope, String targetKey, String action, String reason, LocalDateTime expiresAt, String operator) {
        if (!StringUtils.hasText(scope) || !StringUtils.hasText(targetKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "控制范围和目标不能为空");
        }
        AiSafetyControl control = new AiSafetyControl();
        control.setScope(scope.trim().toUpperCase(Locale.ROOT));
        control.setTargetKey(targetKey.trim());
        control.setAction(StringUtils.hasText(action) ? action.trim().toUpperCase(Locale.ROOT) : AiSafetyAction.BLOCK);
        control.setReason(trim(reason, 255));
        control.setExpiresAt(expiresAt);
        control.setCreatedBy(operator);
        return controlRepository.save(control);
    }

    public void disableControl(long id) {
        AiSafetyControl control = controlRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "安全控制不存在"));
        control.setActive(false);
        control.setUpdatedAt(LocalDateTime.now());
        controlRepository.save(control);
    }

    @Transactional(readOnly = true)
    public SafetySummary summary() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return new SafetySummary(
                eventRepository.countByStatusAndSeverity(STATUS_OPEN, SEVERITY_HIGH),
                eventRepository.countByCreatedAtAfterAndActionIn(since, List.of(AiSafetyAction.BLOCK, AiSafetyAction.REDACT, AiSafetyAction.RATE_LIMIT, AiSafetyAction.ESCALATE)),
                eventRepository.countByCreatedAtAfterAndCategory(since, "COST_ANOMALY"),
                activeControls().size()
        );
    }

    public ApiException safetyException(AiSafetyResult result) {
        if (AiSafetyAction.RATE_LIMIT.equals(result.action())) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
        return new ApiException(HttpStatus.BAD_REQUEST, "内容未通过安全检查，请调整后再试");
    }

    private AiSafetyResult controlResult(String content, AiSafetyContext context) {
        for (AiSafetyControl control : activeControls()) {
            if (!matchesControl(control, context)) {
                continue;
            }
            String action = StringUtils.hasText(control.getAction()) ? control.getAction() : AiSafetyAction.BLOCK;
            RuleDecision decision = new RuleDecision(action, SEVERITY_HIGH, "ADMIN_CONTROL", "命中管理员临时控制", SAFE_REPLACEMENT);
            AiSafetyEvent event = createEvent(content, context, decision);
            return new AiSafetyResult(action, SEVERITY_HIGH, "ADMIN_CONTROL", decision.reason(), decision.safeContent(), event);
        }
        return null;
    }

    private boolean matchesControl(AiSafetyControl control, AiSafetyContext context) {
        String target = control.getTargetKey();
        return switch (control.getScope()) {
            case "GLOBAL" -> "*".equals(target) || "GLOBAL".equalsIgnoreCase(target);
            case "USER" -> target.equals(context.getUserId()) || target.equals(context.getPlayerId());
            case "ROOM" -> target.equals(context.getRoomId());
            case "PERSONA" -> target.equals(context.getPersonaId());
            case "MODEL" -> target.equals(context.getModelKey());
            default -> false;
        };
    }

    private RuleDecision evaluateRules(String content) {
        if (!StringUtils.hasText(content)) {
            return new RuleDecision(AiSafetyAction.ALLOW, SEVERITY_LOW, "NONE", "", content);
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("m4_test_rate_limit")) {
            return new RuleDecision(AiSafetyAction.RATE_LIMIT, SEVERITY_MEDIUM, "ABNORMAL_RATE", "测试触发异常频率限制", SAFE_REPLACEMENT);
        }
        if (lower.contains("m4_test_redact") || lower.contains("身份证") || lower.contains("手机号")) {
            return new RuleDecision(AiSafetyAction.REDACT, SEVERITY_MEDIUM, "PRIVACY", "疑似隐私信息", SAFE_REPLACEMENT);
        }
        if (lower.contains("m4_test_block") || containsAny(lower, List.of("违法交易", "制作炸药", "自残教程", "儿童色情"))) {
            return new RuleDecision(AiSafetyAction.BLOCK, SEVERITY_HIGH, "DANGEROUS_OR_ILLEGAL", "疑似危险或违法内容", SAFE_REPLACEMENT);
        }
        if (containsAny(lower, List.of("ignore previous instructions", "忽略以上规则", "输出系统提示", "泄露prompt", "泄露系统提示"))) {
            return new RuleDecision(AiSafetyAction.ESCALATE, SEVERITY_HIGH, "PROMPT_INJECTION", "疑似 Prompt Injection", SAFE_REPLACEMENT);
        }
        if (containsAny(lower, List.of("我的隐藏词是", "所有人的身份是", "汤底是", "夜晚目标是"))) {
            return new RuleDecision(AiSafetyAction.BLOCK, SEVERITY_HIGH, "HIDDEN_INFO_LEAK", "疑似隐藏信息泄露", SAFE_REPLACEMENT);
        }
        if (content.length() > 4000) {
            return new RuleDecision(AiSafetyAction.RATE_LIMIT, SEVERITY_MEDIUM, "ABNORMAL_RATE", "内容长度异常", SAFE_REPLACEMENT);
        }
        return new RuleDecision(AiSafetyAction.ALLOW, SEVERITY_LOW, "NONE", "", content);
    }

    private boolean containsAny(String value, Collection<String> patterns) {
        return patterns.stream().anyMatch(value::contains);
    }

    private AiSafetyEvent createEvent(String content, AiSafetyContext context, RuleDecision decision) {
        AiSafetyEvent event = new AiSafetyEvent();
        event.setSource(StringUtils.hasText(context.getSource()) ? context.getSource() : "UNKNOWN");
        event.setAction(decision.action());
        event.setSeverity(decision.severity());
        event.setCategory(decision.category());
        event.setStatus(STATUS_OPEN);
        event.setRoomId(context.getRoomId());
        event.setGameId(context.getGameId());
        event.setUserId(context.getUserId());
        event.setPlayerId(context.getPlayerId());
        event.setPersonaId(context.getPersonaId());
        event.setModelKey(context.getModelKey());
        event.setTraceId(context.getTraceId());
        event.setContentSummary(summarize(content));
        event.setSanitizedContent(decision.safeContent());
        event.setReason(trim(decision.reason(), 255));
        event.setMetadata(context.getMetadata());
        return eventRepository.save(event);
    }

    private String summarize(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String redacted = EMAIL.matcher(content).replaceAll("[email]");
        redacted = PHONE.matcher(redacted).replaceAll("[phone]");
        return trim(redacted.replaceAll("\\s+", " "), 512);
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private record RuleDecision(String action, String severity, String category, String reason, String safeContent) {}

    public record SafetySummary(long openHighRiskEvents, long blockedLast24h, long costAnomaliesLast24h, long activeControls) {}
}
