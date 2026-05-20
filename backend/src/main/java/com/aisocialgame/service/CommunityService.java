package com.aisocialgame.service;

import com.aisocialgame.dto.CommunityPostRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.CommunityPost;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommunityService {
    private final CommunityPostRepository repository;
    private final AiSafetyService aiSafetyService;

    public CommunityService(CommunityPostRepository repository, AiSafetyService aiSafetyService) {
        this.repository = repository;
        this.aiSafetyService = aiSafetyService;
    }

    public List<CommunityPost> list() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    public CommunityPost create(CommunityPostRequest request, User user, String guestName) {
        CommunityPost post = new CommunityPost();
        String normalizedGuestName = normalizeGuestName(guestName);
        String authorKey = user != null ? user.getId() : (normalizedGuestName == null ? "guest" : "guest:" + normalizedGuestName.trim());
        String safeContent = aiSafetyService.requireAllowedInput(
                request.getContent(),
                AiSafetyContext.source(AiSafetyService.SOURCE_COMMUNITY).user(authorKey, authorKey)
        );
        post.setContent(safeContent.trim());
        post.setTags(request.getTags() == null ? new ArrayList<>() : request.getTags());
        if (user != null) {
            post.setAuthorId(user.getId());
            post.setAuthorName(user.getNickname());
            post.setAvatar(user.getAvatar());
        } else {
            String name = (normalizedGuestName != null && !normalizedGuestName.isBlank()) ? normalizedGuestName.trim() : "游客" + UUID.randomUUID().toString().substring(0, 6);
            post.setAuthorName(name);
            post.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + name.replace(" ", ""));
        }
        return repository.save(post);
    }

    public CommunityPost like(String id) {
        CommunityPost post = repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
        post.setLikes(post.getLikes() + 1);
        return repository.save(post);
    }

    private String normalizeGuestName(String guestName) {
        if (guestName == null || guestName.isBlank()) {
            return null;
        }
        String trimmed = guestName.trim();
        try {
            return URLDecoder.decode(trimmed, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return trimmed;
        }
    }
}
