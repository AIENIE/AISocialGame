package com.aisocialgame.lab;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "lab_agents")
public class LabAgent {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(nullable = false, length = 64) public String name;
    @Column(nullable = false, length = 64) public String personaId;
    @Convert(converter = MapToJsonConverter.class)
    @Column(nullable = false, columnDefinition = "LONGTEXT") public Map<String, Object> modelSelection = new LinkedHashMap<>();
    @Column(nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
    @Version public Long version;
}
