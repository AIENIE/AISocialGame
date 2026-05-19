package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;

public class JoinRoomRequest {
    @NotBlank
    private String displayName;
    private String password;

    public String getDisplayName() { return displayName; }
    public String getPassword() { return password; }
}
