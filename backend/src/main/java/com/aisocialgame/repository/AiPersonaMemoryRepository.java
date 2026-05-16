package com.aisocialgame.repository;

import com.aisocialgame.model.AiPersonaMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiPersonaMemoryRepository extends JpaRepository<AiPersonaMemory, Long> {
    Optional<AiPersonaMemory> findByPersonaIdAndGameIdAndRoleKey(String personaId, String gameId, String roleKey);

    List<AiPersonaMemory> findByPersonaIdOrderByUpdatedAtDesc(String personaId);
}
