package com.aisocialgame.controller;

import com.aisocialgame.dto.BalanceView;
import com.aisocialgame.dto.CheckinResponse;
import com.aisocialgame.dto.CheckinStatusResponse;
import com.aisocialgame.dto.ExchangeRequest;
import com.aisocialgame.dto.ExchangeResponse;
import com.aisocialgame.dto.ExchangeHistoryView;
import com.aisocialgame.dto.LedgerEntryView;
import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.RedeemRequest;
import com.aisocialgame.dto.RedeemResponse;
import com.aisocialgame.dto.RedemptionView;
import com.aisocialgame.dto.UsageRecordView;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.model.User;
import com.aisocialgame.service.WalletService;
import com.aisocialgame.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/checkin")
    public ResponseEntity<CheckinResponse> checkin(@CurrentUser User user) {
        return ResponseEntity.ok(new CheckinResponse(walletService.checkin(user)));
    }

    @GetMapping("/checkin-status")
    public ResponseEntity<CheckinStatusResponse> checkinStatus(@CurrentUser User user) {
        return ResponseEntity.ok(new CheckinStatusResponse(walletService.getCheckinStatus(user)));
    }

    @GetMapping("/balance")
    public ResponseEntity<BalanceView> balance(@CurrentUser User user) {
        BalanceSnapshot snapshot = walletService.getBalance(user);
        return ResponseEntity.ok(new BalanceView(snapshot));
    }

    @GetMapping("/usage-records")
    public ResponseEntity<PagedResponse<UsageRecordView>> usageRecords(@CurrentUser User user,
                                                                       @RequestParam(defaultValue = "1") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        var result = walletService.getUsageRecords(user, page, size);
        List<UsageRecordView> items = result.items().stream().map(UsageRecordView::new).toList();
        return ResponseEntity.ok(new PagedResponse<>(items, result.page(), result.size(), result.total()));
    }

    @GetMapping("/ledger")
    public ResponseEntity<PagedResponse<LedgerEntryView>> ledger(@CurrentUser User user,
                                                                 @RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        var result = walletService.getLedgerEntries(user, page, size);
        List<LedgerEntryView> items = result.items().stream().map(LedgerEntryView::new).toList();
        return ResponseEntity.ok(new PagedResponse<>(items, result.page(), result.size(), result.total()));
    }

    @PostMapping("/redeem")
    public ResponseEntity<RedeemResponse> redeem(@CurrentUser User user,
                                                 @Valid @RequestBody RedeemRequest request) {
        return ResponseEntity.ok(new RedeemResponse(walletService.redeemCode(user, request.getCode())));
    }

    @PostMapping("/exchange/public-to-project")
    public ResponseEntity<ExchangeResponse> exchangePublicToProject(
            @CurrentUser User user,
            @Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.ok(new ExchangeResponse(
                walletService.exchangePublicToProject(user, request.getAmount(), request.getRequestId())
        ));
    }

    @GetMapping("/redemption-history")
    public ResponseEntity<PagedResponse<RedemptionView>> redemptionHistory(@CurrentUser User user,
                                                                           @RequestParam(defaultValue = "1") int page,
                                                                           @RequestParam(defaultValue = "20") int size) {
        var result = walletService.getRedemptionHistory(user, page, size);
        List<RedemptionView> items = result.items().stream().map(RedemptionView::new).toList();
        return ResponseEntity.ok(new PagedResponse<>(items, result.page(), result.size(), result.total()));
    }

    @GetMapping("/exchange-history")
    public ResponseEntity<PagedResponse<ExchangeHistoryView>> exchangeHistory(
            @CurrentUser User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = walletService.getExchangeHistory(user, page, size);
        List<ExchangeHistoryView> items = result.items().stream().map(ExchangeHistoryView::new).toList();
        return ResponseEntity.ok(new PagedResponse<>(items, result.page(), result.size(), result.total()));
    }
}
