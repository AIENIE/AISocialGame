package com.aisocialgame.model.credit;

import jakarta.persistence.*;
import java.time.Instant;

/** Returned escrow keeps its original expiry independently of subsequent grants. */
@Entity
@Table(name = "credit_temp_lots", indexes = @Index(name="idx_temp_lot_owner", columnList="user_id,project_key"))
public class CreditTempLot {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="user_id", nullable=false) public long userId;
    @Column(name="project_key", nullable=false, length=64) public String projectKey;
    @Column(nullable=false) public long remaining;
    @Column(name="expires_at") public Instant expiresAt;
}
