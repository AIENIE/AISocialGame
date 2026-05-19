package com.aisocialgame.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class AiChatRequest {
    private String model;

    @Valid
    @NotEmpty
    @Size(max = 20)
    private List<AiMessageRequest> messages;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<AiMessageRequest> getMessages() {
        return messages;
    }

    public void setMessages(List<AiMessageRequest> messages) {
        this.messages = messages;
    }
}
