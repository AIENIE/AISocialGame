package com.aisocialgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddAiRequest {
    @NotBlank
    @Size(max = 64)
    private String personaId;

    public String getPersonaId() { return personaId; }
}
