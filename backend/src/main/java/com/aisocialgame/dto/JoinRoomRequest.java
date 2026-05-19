package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class JoinRoomRequest {
    @NotBlank
    @Size(max = 64)
    private String displayName;
    @Size(max = 128)
    private String password;

    public String getDisplayName() { return displayName; }
    public String getPassword() { return password; }
}
