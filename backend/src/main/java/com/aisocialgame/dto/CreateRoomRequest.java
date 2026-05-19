package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public class CreateRoomRequest {
    @NotBlank
    @Size(max = 64)
    private String roomName;
    @NotNull
    private Boolean isPrivate;
    @Size(max = 128)
    private String password;
    @Pattern(regexp = "^(text|voice|video)?$", message = "commMode 不支持")
    private String commMode;
    @Size(max = 32)
    private Map<String, Object> config;

    public String getRoomName() { return roomName; }
    public Boolean getIsPrivate() { return isPrivate; }
    public String getPassword() { return password; }
    public String getCommMode() { return commMode; }
    public Map<String, Object> getConfig() { return config; }
}
