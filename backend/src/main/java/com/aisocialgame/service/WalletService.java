package com.aisocialgame.service;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.CheckinResult;
import com.aisocialgame.integration.grpc.dto.CheckinStatusResult;
import com.aisocialgame.integration.grpc.dto.LedgerEntrySnapshot;
import com.aisocialgame.integration.grpc.dto.PagedResult;
import com.aisocialgame.integration.grpc.dto.RedeemResult;
import com.aisocialgame.integration.grpc.dto.RedemptionRecordSnapshot;
import com.aisocialgame.integration.grpc.dto.ExchangeHistorySnapshot;
import com.aisocialgame.integration.grpc.dto.UsageRecordSnapshot;
import com.aisocialgame.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WalletService {
    private final BalanceService balanceService;
    private final ProjectCreditService projectCreditService;

    public WalletService(BalanceService balanceService,
                         ProjectCreditService projectCreditService) {
        this.balanceService = balanceService;
        this.projectCreditService = projectCreditService;
    }

    public CheckinResult checkin(User user) {
        long userId = requireExternalUserId(user);
        return projectCreditService.checkin(userId, readPublicTokens(userId));
    }

    public CheckinStatusResult getCheckinStatus(User user) {
        long userId = requireExternalUserId(user);
        return projectCreditService.getCheckinStatus(userId);
    }

    public BalanceSnapshot getBalance(User user) {
        return balanceService.getDisplayBalance(requireExternalUserId(user));
    }

    public PagedResult<UsageRecordSnapshot> getUsageRecords(User user, int page, int size) {
        long userId = requireExternalUserId(user);
        return projectCreditService.listUsageRecords(userId, normalizePage(page), normalizeSize(size));
    }

    public PagedResult<LedgerEntrySnapshot> getLedgerEntries(User user, int page, int size) {
        long userId = requireExternalUserId(user);
        return projectCreditService.listLedgerEntries(userId, normalizePage(page), normalizeSize(size));
    }

    public RedeemResult redeemCode(User user, String code) {
        long userId = requireExternalUserId(user);
        if (!StringUtils.hasText(code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "兑换码不能为空");
        }
        return projectCreditService.redeemCode(userId, code.trim(), readPublicTokens(userId));
    }

    public PagedResult<RedemptionRecordSnapshot> getRedemptionHistory(User user, int page, int size) {
        long userId = requireExternalUserId(user);
        return projectCreditService.getRedemptionHistory(userId, normalizePage(page), normalizeSize(size));
    }

    public CreditExchangeResult exchangePublicToProject(User user, long tokens, String requestId) {
        long userId = requireExternalUserId(user);
        return projectCreditService.exchangePublicToProject(userId, tokens, requestId);
    }

    public PagedResult<ExchangeHistorySnapshot> getExchangeHistory(User user, int page, int size) {
        long userId = requireExternalUserId(user);
        return projectCreditService.listExchangeHistory(userId, normalizePage(page), normalizeSize(size));
    }

    private long requireExternalUserId(User user) {
        if (user == null || user.getExternalUserId() == null || user.getExternalUserId() <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return user.getExternalUserId();
    }

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private long readPublicTokens(long userId) {
        try {
            return balanceService.getUserBalance(userId).publicPermanentTokens();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
