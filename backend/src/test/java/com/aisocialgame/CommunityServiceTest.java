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
    void createShouldDecodeGuestNameBeforeSavingAndReviewing() {
        CommunityService service = new CommunityService(repository, aiSafetyService);
        when(aiSafetyService.requireAllowedInput(anyString(), any(AiSafetyContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommunityPostRequest request = new CommunityPostRequest();
        request.setContent("社区分享内容");
        request.setTags(List.of("安全", "分享"));

        CommunityPost post = service.create(request, null, "%E6%B5%8B%E8%AF%95%E7%8E%A9%E5%AE%B6");

        ArgumentCaptor<AiSafetyContext> contextCaptor = ArgumentCaptor.forClass(AiSafetyContext.class);
        verify(aiSafetyService).requireAllowedInput(anyString(), contextCaptor.capture());
        assertEquals("guest:测试玩家", contextCaptor.getValue().getUserId());
        assertEquals("测试玩家", post.getAuthorName());
        assertEquals("社区分享内容", post.getContent());
        assertEquals(List.of("安全", "分享"), post.getTags());
    }
}
