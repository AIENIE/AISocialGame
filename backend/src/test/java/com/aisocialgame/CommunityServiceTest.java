package com.aisocialgame;

import com.aisocialgame.dto.CommunityPostRequest;
import com.aisocialgame.model.CommunityPost;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.service.CommunityService;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {

    @Mock
    private CommunityPostRepository repository;
    @Mock
    private AiSafetyService aiSafetyService;

    @Test
    void createBindsAuthorAndSafetySubjectToAuthenticatedAccount() {
        CommunityService service = new CommunityService(repository, aiSafetyService, org.mockito.Mockito.mock(com.aisocialgame.repository.CommunityLikeRepository.class), org.mockito.Mockito.mock(com.aisocialgame.service.WriteRateLimiter.class));
        when(aiSafetyService.requireAllowedInput(anyString(), any(AiSafetyContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommunityPostRequest request = new CommunityPostRequest();
        request.setContent("社区分享内容");
        request.setTags(List.of("安全", "分享"));

        var user = new com.aisocialgame.model.User(); user.setId("account"); user.setNickname("测试玩家");
        CommunityPost post = service.create(request, user);

        ArgumentCaptor<AiSafetyContext> contextCaptor = ArgumentCaptor.forClass(AiSafetyContext.class);
        verify(aiSafetyService).requireAllowedInput(anyString(), contextCaptor.capture());
        assertEquals("account", contextCaptor.getValue().getUserId());
        assertEquals("测试玩家", post.getAuthorName());
        assertEquals("社区分享内容", post.getContent());
        assertEquals(List.of("安全", "分享"), post.getTags());
    }
    @Test void anonymousWritesAreRejectedBeforeAnyWork() {
        var service = new CommunityService(repository, aiSafetyService, org.mockito.Mockito.mock(com.aisocialgame.repository.CommunityLikeRepository.class), org.mockito.Mockito.mock(com.aisocialgame.service.WriteRateLimiter.class));
        var error = org.junit.jupiter.api.Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> service.create(new CommunityPostRequest(), null));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, error.getStatus());
        org.junit.jupiter.api.Assertions.assertThrows(com.aisocialgame.exception.ApiException.class, () -> service.like("post", null));
        org.mockito.Mockito.verifyNoInteractions(repository, aiSafetyService);
    }

}
