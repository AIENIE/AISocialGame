package com.aisocialgame.model;

import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_function_model_configs")
public class AiFunctionModelConfig {
    @Id @Column(length = 40) public String functionCode;
    @Column(length = 128) public String modelKey;
    @Enumerated(EnumType.STRING) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16) public ThinkingMode thinkingMode = ThinkingMode.DEFAULT;
    @Column(length = 16) public String thinkingEffort;
    @Column(nullable = false) public LocalDateTime updatedAt = LocalDateTime.now();
    @Version public Long version;
}
