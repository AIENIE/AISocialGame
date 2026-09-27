package com.aisocialgame.repository;

import com.aisocialgame.model.GameEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import com.aisocialgame.model.GameEventVisibility;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameEventRepository extends JpaRepository<GameEvent, Long> {
    List<GameEvent> findByArchiveIdOrderBySeqAsc(String archiveId);

    long countByArchiveId(String archiveId);

    @Query("select coalesce(max(e.seq), 0) from GameEvent e where e.archiveId = :archiveId")
    int maxSeq(@Param("archiveId") String archiveId);

    @Query("select coalesce(max(e.publicSeq), 0) from GameEvent e where e.archiveId = :archiveId and e.visibility = :visibility")
    long maxPublicSeq(@Param("archiveId") String archiveId, @Param("visibility") GameEventVisibility visibility);

    @Query("select e from GameEvent e where e.archiveId = :archiveId and e.visibility = :visibility and "
            + "(:before is null or e.publicSeq < :before) order by e.publicSeq desc")
    List<GameEvent> publicPage(@Param("archiveId") String archiveId,
                               @Param("visibility") GameEventVisibility visibility,
                               @Param("before") Long before, Pageable page);
}
