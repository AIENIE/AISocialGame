package com.aisocialgame.dto.admin;

import jakarta.validation.constraints.*;

public record AdminMemoryReviewRequest(
        @NotNull @Pattern(regexp = "APPROVED|REJECTED") String status,
        @NotNull @Size(max = 1200) String summary) {}
