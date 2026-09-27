package com.aisocialgame.service.safety;
import com.aisocialgame.exception.ApiException;
import org.springframework.http.HttpStatus;
public final class AiCallBlockedException extends ApiException {
    public enum Reason { CALL_BUDGET_EXHAUSTED, CALL_RATE_LIMITED, ADMIN_CONTROL }
    private final Reason reason;
    public AiCallBlockedException(Reason reason) { super(HttpStatus.TOO_MANY_REQUESTS,"AI 调用当前不可用，请稍后再试");this.reason=reason; }
    public Reason reason(){return reason;}
}
