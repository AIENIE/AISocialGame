package com.aisocialgame.lab;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lab_enrollments", uniqueConstraints = @UniqueConstraint(name = "uk_lab_enrollment_user", columnNames = {"experiment_id", "user_id"}))
public class LabEnrollment {
    @Id @Column(length = 36) public String id = UUID.randomUUID().toString();
    @Column(name = "experiment_id", nullable = false, length = 36) public String experimentId;
    @Column(name = "user_id", nullable = false, length = 36) public String userId;
    @Column(nullable = false, length = 64) public String displayName;
    @Column(length = 512) public String avatar;
    @Column(nullable = false, length = 24) public String status = "WAITING";
    @Column(length = 36) public String matchId;
    @Column public Long confirmUntil;
    @Column(nullable = false) public LocalDateTime joinedAt = LocalDateTime.now();
    @Version public Long version;
}
