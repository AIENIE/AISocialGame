package com.aisocialgame.repository;

import com.aisocialgame.model.GameArchive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameArchiveRepository extends JpaRepository<GameArchive, String> {
    Page<GameArchive> findByGameId(String gameId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        select a from GameArchive a
        where (:gameId is null or a.gameId=:gameId)
          and (:fromTime is null or a.finishedAt>=:fromTime)
          and (:toTime is null or a.finishedAt<=:toTime)
          and (:playerId is null or exists (select p from ArchiveParticipant p where p.id.archiveId=a.id and p.id.playerId=:playerId))
          and (a.publicReplay=true or a.hostUserId=:viewerId or exists (select p from ArchiveParticipant p where p.id.archiveId=a.id and p.id.playerId=:viewerId))
        """)
    Page<GameArchive> searchVisible(@org.springframework.data.repository.query.Param("gameId") String gameId,
                                    @org.springframework.data.repository.query.Param("playerId") String playerId,
                                    @org.springframework.data.repository.query.Param("viewerId") String viewerId,
                                    @org.springframework.data.repository.query.Param("fromTime") java.time.LocalDateTime from,
                                    @org.springframework.data.repository.query.Param("toTime") java.time.LocalDateTime to,
                                    Pageable pageable);
}
