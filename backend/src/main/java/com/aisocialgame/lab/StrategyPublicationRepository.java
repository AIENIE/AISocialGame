package com.aisocialgame.lab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StrategyPublicationRepository extends JpaRepository<StrategyPublication,String> {
    Optional<StrategyPublication> findByOperationId(String operationId);
}
