package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.BalanceView;
import com.aisocialgame.dto.admin.AdminAdjustBalanceRequest;
import com.aisocialgame.dto.admin.AdminCreateRedeemCodeRequest;
import com.aisocialgame.dto.admin.AdminLedgerPageResponse;
import com.aisocialgame.dto.admin.AdminMigrateAllBalanceRequest;
import com.aisocialgame.dto.admin.AdminMigrateAllBalanceResponse;
import com.aisocialgame.dto.admin.AdminMigrateBalanceRequest;
import com.aisocialgame.dto.admin.AdminRedeemCodeResponse;
import com.aisocialgame.dto.admin.AdminReverseBalanceRequest;
import com.aisocialgame.service.AdminOpsService;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/billing")
public class AdminBillingController {
    private final AdminOpsService adminOpsService;

    public AdminBillingController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/balance")
    public ResponseEntity<BalanceView> balance(@RequestParam long userId,
                                               @CurrentAdmin String operator) {
        return ResponseEntity.ok(new BalanceView(adminOpsService.getBalance(userId)));
    }

    @GetMapping("/ledger")
    public ResponseEntity<AdminLedgerPageResponse> ledger(@RequestParam long userId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.getLedger(userId, page, size));
    }

    @PostMapping("/adjust")
    public ResponseEntity<BalanceView> adjust(@Valid @RequestBody AdminAdjustBalanceRequest request,
                                              @CurrentAdmin String operator) {
        return ResponseEntity.ok(new BalanceView(
                adminOpsService.adjustBalance(request.getUserId(), request.getDeltaTemp(), request.getDeltaPermanent(), request.getReason(), operator, request.getRequestId())
        ));
    }

    @PostMapping("/reversal")
    public ResponseEntity<BalanceView> reversal(@Valid @RequestBody AdminReverseBalanceRequest request,
                                                @CurrentAdmin String operator) {
        return ResponseEntity.ok(new BalanceView(
                adminOpsService.reverseBalance(request.getUserId(), request.getOriginalRequestId(), request.getReason(), operator)
        ));
    }

    @PostMapping("/migrate-user")
    public ResponseEntity<BalanceView> migrateUser(@Valid @RequestBody AdminMigrateBalanceRequest request,
                                                   @CurrentAdmin String operator) {
        return ResponseEntity.ok(new BalanceView(adminOpsService.migrateUserBalance(request.getUserId(), operator)));
    }

    @PostMapping("/migrate-all")
    public ResponseEntity<AdminMigrateAllBalanceResponse> migrateAllUsers(
            @Valid @RequestBody(required = false) AdminMigrateAllBalanceRequest request,
            @CurrentAdmin String operator) {
        Integer batchSize = request == null ? null : request.getBatchSize();
        return ResponseEntity.ok(adminOpsService.migrateAllUsersBalance(operator, batchSize));
    }

    @PostMapping("/redeem-codes")
    public ResponseEntity<AdminRedeemCodeResponse> createRedeemCode(@Valid @RequestBody AdminCreateRedeemCodeRequest request,
                                                                     @CurrentAdmin String operator) {
        return ResponseEntity.ok(new AdminRedeemCodeResponse(
                adminOpsService.createRedeemCode(
                        request.getCode(),
                        request.getTokens(),
                        request.getCreditType(),
                        request.getMaxRedemptions(),
                        request.getValidFrom(),
                        request.getValidUntil(),
                        request.getActive()
                )
        ));
    }
}
