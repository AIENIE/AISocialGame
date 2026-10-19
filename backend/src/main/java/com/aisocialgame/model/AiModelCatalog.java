package com.aisocialgame.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_model_catalog")
public class AiModelCatalog {
    @Id public Long id = 1L;
    @Column(nullable = false, columnDefinition = "LONGTEXT") public String modelsJson = "[]";
    public LocalDateTime syncedAt;
    @Version public Long version;
}
