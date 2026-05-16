package com.aisocialgame.repository;

import com.aisocialgame.model.GameArchive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameArchiveRepository extends JpaRepository<GameArchive, String> {
    Page<GameArchive> findByGameId(String gameId, Pageable pageable);
}
