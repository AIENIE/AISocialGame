package com.aisocialgame.model;

import jakarta.persistence.*;

/** Stable lock even when a historical participant no longer has a user row. */
@Entity
@Table(name = "player_settlement_locks")
public class PlayerSettlementLock {
    @Id @Column(length = 36) private String playerId;
    protected PlayerSettlementLock() {}
}
