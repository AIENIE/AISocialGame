package com.aisocialgame.repository;

import com.aisocialgame.model.ArchiveParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchiveParticipantRepository extends JpaRepository<ArchiveParticipant, ArchiveParticipant.Key> { }
