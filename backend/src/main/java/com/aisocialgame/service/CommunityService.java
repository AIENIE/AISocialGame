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
    private final com.aisocialgame.repository.CommunityLikeRepository likes;
    private final WriteRateLimiter limiter;
    @org.springframework.beans.factory.annotation.Value("${app.community.posts-per-minute:5}")
    private int postsPerMinute = 5;

    public CommunityService(CommunityPostRepository repository, AiSafetyService aiSafetyService,
                            com.aisocialgame.repository.CommunityLikeRepository likes, WriteRateLimiter limiter) {
        this.likes = likes; this.limiter = limiter;
        this.repository = repository;
        this.aiSafetyService = aiSafetyService;
    }

    public List<CommunityPost> list() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    public CommunityPost create(CommunityPostRequest request, User user) {
        requireUser(user);
        if (request.getTags() != null && (request.getTags().size() > 10 || request.getTags().stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 32))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "标签最多 10 个，每个最多 32 个字符");
        }
        limiter.require(user.getId(), List.of(new WriteRateLimiter.Limit("community-post", postsPerMinute)));
        CommunityPost post = new CommunityPost();
        String safeContent = aiSafetyService.requireAllowedInput(request.getContent(),
                AiSafetyContext.source(AiSafetyService.SOURCE_COMMUNITY).user(user.getId(), user.getId()));
        post.setContent(safeContent.trim());
        post.setTags(request.getTags() == null ? new ArrayList<>() : List.copyOf(request.getTags()));
        post.setAuthorId(user.getId()); post.setAuthorName(user.getNickname()); post.setAvatar(user.getAvatar());
        return repository.save(post);
    }

    public CommunityPost like(String id, User user) {
        requireUser(user);
        // Serialize the aggregate update with voter insertion. The composite PK is the persistent idempotency key.
        CommunityPost post = repository.findByIdForUpdate(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
        var key = new com.aisocialgame.model.CommunityLike.Key(id, user.getId());
        if (!likes.existsById(key)) {
            likes.save(new com.aisocialgame.model.CommunityLike(id, user.getId()));
            post.setLikes(Math.addExact(post.getLikes(), 1));
            repository.save(post);
        }
        return post;
    }

    private void requireUser(User user) {
        if (user == null || user.getId() == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
