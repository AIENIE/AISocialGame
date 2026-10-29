package com.aisocialgame.service;

import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.model.User;
import org.springframework.stereotype.Service;

@Service
public class BalanceService {
    private final BillingGrpcClient billingGrpcClient;
    private final ProjectCreditService projectCreditService;

    public BalanceService(BillingGrpcClient billingGrpcClient, ProjectCreditService projectCreditService) {
        this.billingGrpcClient = billingGrpcClient;
        this.projectCreditService = projectCreditService;
    }

    /** Read-only display snapshot: a failed dependency is never represented as a zero balance. */
    public BalanceSnapshot getDisplayBalance(User user) {
        if (user == null || user.getExternalUserId() == null) {
            throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录");
        }
        return getDisplayBalance(user.getExternalUserId());
    }

    public BalanceSnapshot getDisplayBalance(long externalUserId) {
        if (externalUserId <= 0) throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录");
        long publicTokens = billingGrpcClient.getPublicPermanentTokens(externalUserId);
        return projectCreditService.getBalance(externalUserId, publicTokens);
    }

}
