package com.aisocialgame.repository;

import com.aisocialgame.model.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommunityPostRepository extends JpaRepository<CommunityPost, String> {
    List<CommunityPost> findTop50ByOrderByCreatedAtDesc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from CommunityPost p where p.id=:id")
    java.util.Optional<CommunityPost> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
