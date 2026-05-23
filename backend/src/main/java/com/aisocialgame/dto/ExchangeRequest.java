package com.aisocialgame.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ExchangeRequest {
    @NotNull(message = "兑换数量不能为空")
    @Min(value = 1, message = "兑换数量必须大于 0")
    private Long amount;

    @NotBlank(message = "requestId 不能为空")
    @Size(max = 128, message = "requestId 过长")
    private String requestId;

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
