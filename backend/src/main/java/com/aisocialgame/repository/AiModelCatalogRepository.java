package com.aisocialgame.repository;

import com.aisocialgame.model.AiModelCatalog;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface AiModelCatalogRepository extends JpaRepository<AiModelCatalog, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AiModelCatalog c where c.id = 1")
    Optional<AiModelCatalog> lockSingleton();
}
