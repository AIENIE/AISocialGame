package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AiMessageRequest {
    @NotBlank
    @Size(max = 32)
    private String role;

    @NotBlank
    @Size(max = 4000)
    private String content;

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
