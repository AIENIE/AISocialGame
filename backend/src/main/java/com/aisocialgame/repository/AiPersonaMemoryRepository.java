package com.aisocialgame.repository;

import com.aisocialgame.model.AiPersonaMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiPersonaMemoryRepository extends JpaRepository<AiPersonaMemory, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO ai_persona_memories(persona_id,game_id,role_key,games_played,review_status,created_at,updated_at)
            VALUES(:personaId,:gameId,'GENERAL_V2',1,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE games_played=games_played+1,updated_at=CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void incrementGamesPlayed(@org.springframework.data.repository.query.Param("personaId") String personaId,
                             @org.springframework.data.repository.query.Param("gameId") String gameId);
    Optional<AiPersonaMemory> findByPersonaIdAndGameIdAndRoleKey(String personaId, String gameId, String roleKey);

    List<AiPersonaMemory> findByPersonaIdOrderByUpdatedAtDesc(String personaId);
}
