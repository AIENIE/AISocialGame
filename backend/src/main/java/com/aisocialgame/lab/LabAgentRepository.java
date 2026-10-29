package com.aisocialgame.lab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LabAgentRepository extends JpaRepository<LabAgent, String> {
    List<LabAgent> findAllByOrderByCreatedAtAsc();
}
