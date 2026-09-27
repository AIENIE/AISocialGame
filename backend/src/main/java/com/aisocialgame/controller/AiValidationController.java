package com.aisocialgame.controller;

import com.aisocialgame.model.User;
import com.aisocialgame.service.ai.v2.AiCallBudgetService;
import com.aisocialgame.web.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/games/ai-validation-budget")
public class AiValidationController {
    private final AiCallBudgetService budget;
    public AiValidationController(AiCallBudgetService budget) { this.budget = budget; }
    @GetMapping public Map<String, Object> status(@CurrentUser User user) {
        return Map.of("enabled", budget.limit() > 0, "limit", budget.limit(), "consumed", budget.consumed(), "runId", budget.runId());
    }
}
