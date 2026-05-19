package com.aisocialgame.controller;

import com.aisocialgame.exception.GlobalExceptionHandler;
import com.aisocialgame.service.AdminAuthService;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.service.WalletService;
import com.aisocialgame.web.CurrentAdminArgumentResolver;
import com.aisocialgame.web.CurrentUserArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
@Import({GlobalExceptionHandler.class, CurrentUserArgumentResolver.class, CurrentAdminArgumentResolver.class, WalletControllerTest.ArgumentResolverConfig.class})
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AdminAuthService adminAuthService;

    @MockBean
    private WalletService walletService;

    @TestConfiguration
    static class ArgumentResolverConfig {
        @Bean
        WebMvcConfigurer testArgumentResolvers(CurrentUserArgumentResolver currentUserArgumentResolver,
                                               CurrentAdminArgumentResolver currentAdminArgumentResolver) {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(currentUserArgumentResolver);
                    resolvers.add(currentAdminArgumentResolver);
                }
            };
        }
    }

    @Test
    void balanceWithoutTokenShouldReturnUnauthorized() throws Exception {
        when(authService.authenticate(null)).thenReturn(null);

        mockMvc.perform(get("/api/wallet/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verify(authService).authenticate(null);
        verifyNoInteractions(walletService);
    }
}
