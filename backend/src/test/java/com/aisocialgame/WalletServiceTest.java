package com.aisocialgame;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.CheckinResult;
import com.aisocialgame.integration.grpc.dto.PagedResult;
import com.aisocialgame.model.User;
import com.aisocialgame.service.BalanceService;
import com.aisocialgame.service.ProjectCreditService;
import com.aisocialgame.service.WalletService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private BalanceService balanceService;
    @Mock
    private ProjectCreditService projectCreditService;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(balanceService, projectCreditService);
    }

    @Test
    void checkinShouldCallProjectCreditService() {
        User user = mockUser(1001L);
        when(balanceService.getUserBalance(1001L)).thenReturn(new BalanceSnapshot(8, 0, 0, null));
        when(projectCreditService.checkin(anyLong(), anyLong()))
                .thenReturn(new CheckinResult(true, 100, false, "", BalanceSnapshot.empty()));

        walletService.checkin(user);

        verify(projectCreditService).checkin(1001L, 8L);
    }

    @Test
    void redeemCodeShouldRejectBlankCode() {
        User user = mockUser(1001L);
        Assertions.assertThrows(ApiException.class, () -> walletService.redeemCode(user, " "));
    }

    @Test
    void usageRecordsShouldNormalizePageAndSize() {
        User user = mockUser(1002L);
        when(projectCreditService.listUsageRecords(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PagedResult<>(1, 20, 0, List.of()));

        walletService.getUsageRecords(user, 0, 999);
        verify(projectCreditService).listUsageRecords(1002L, 1, 100);
    }

    private User mockUser(long externalUserId) {
        User user = new User();
        user.setExternalUserId(externalUserId);
        user.setSessionId("session-" + externalUserId);
        user.setNickname("tester");
        return user;
    }
    @Test
    void walletDisplayUsesStrictBalanceRead() {
        when(balanceService.getDisplayBalance(1001L)).thenThrow(new IllegalStateException("unavailable"));
        Assertions.assertThrows(IllegalStateException.class, () -> walletService.getBalance(mockUser(1001L)));
    }

}
