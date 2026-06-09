package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;

public class SsoCallbackRequest {
    @NotBlank(message = "code 不能为空")
    private String code;
    @NotBlank(message = "redirect 不能为空")
    private String redirect;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRedirect() {
        return redirect;
    }

    public void setRedirect(String redirect) {
        this.redirect = redirect;
    }
}
