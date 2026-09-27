package com.aisocialgame.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "community_likes")
public class CommunityLike {
    @EmbeddedId private Key id;
    protected CommunityLike() { }
    public CommunityLike(String postId, String userId) { id = new Key(postId, userId); }

    @Embeddable public static class Key implements Serializable {
        @Column(name = "post_id", length = 36) private String postId;
        @Column(name = "user_id", length = 36) private String userId;
        protected Key() { }
        public Key(String postId, String userId) { this.postId = postId; this.userId = userId; }
        @Override public boolean equals(Object value) { return value instanceof Key other && Objects.equals(postId, other.postId) && Objects.equals(userId, other.userId); }
        @Override public int hashCode() { return Objects.hash(postId, userId); }
    }
}
