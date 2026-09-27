package com.aisocialgame.repository;

import com.aisocialgame.model.CommunityLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityLikeRepository extends JpaRepository<CommunityLike, CommunityLike.Key> { }
