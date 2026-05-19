package com.aisocialgame.controller;

import com.aisocialgame.dto.CommunityPostRequest;
import com.aisocialgame.model.CommunityPost;
import com.aisocialgame.model.User;
import com.aisocialgame.service.CommunityService;
import com.aisocialgame.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/community")
public class CommunityController {
    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping("/posts")
    public ResponseEntity<List<CommunityPost>> list() {
        return ResponseEntity.ok(communityService.list());
    }

    @PostMapping("/posts")
    public ResponseEntity<CommunityPost> create(@Valid @RequestBody CommunityPostRequest request,
                                                @CurrentUser(required = false) User user,
                                                @RequestHeader(value = "X-Guest-Name", required = false) String guestName) {
        CommunityPost post = communityService.create(request, user, guestName);
        return ResponseEntity.ok(post);
    }

    @PostMapping("/posts/{id}/like")
    public ResponseEntity<CommunityPost> like(@PathVariable String id) {
        return ResponseEntity.ok(communityService.like(id));
    }
}
