package com.aisocialgame.controller.admin;

import com.aisocialgame.service.ai.v2.AiValidationBudgetService;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai/validation-runs")
public class AdminAiValidationController {
    public record BudgetRequest(@NotNull @Positive @Digits(integer = 19, fraction = 0)
                                @DecimalMax("9223372036854775807") BigDecimal budgetPoints) { }
    private final AiValidationBudgetService budgets;

    public AdminAiValidationController(AiValidationBudgetService budgets) { this.budgets = budgets; }

    @PutMapping("/{runId}/rooms/{roomId}")
    public AiValidationBudgetService.RunStatus register(@CurrentAdmin String operator, @PathVariable String runId,
            @PathVariable String roomId, @Valid @RequestBody BudgetRequest request) {
        return budgets.registerRoom(runId, roomId, request.budgetPoints().longValueExact());
    }

    @GetMapping("/{runId}")
    public AiValidationBudgetService.RunStatus status(@CurrentAdmin String operator, @PathVariable String runId) {
        return budgets.status(runId);
    }
}
