package com.aisocialgame.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "player_settlements")
public class PlayerSettlement {
    @EmbeddedId private Key id;
    @Column(length = 32, nullable = false) private String gameId;
    private boolean won;
    private int scoreDelta;
    private int coinDelta;
    private LocalDateTime createdAt;
    protected PlayerSettlement() {}
    @Embeddable public static class Key implements Serializable {
        @Column(length = 96) private String archiveId;
        @Column(length = 36) private String playerId;
        protected Key() {}
        @Override public boolean equals(Object value) { return value instanceof Key key && Objects.equals(archiveId,key.archiveId) && Objects.equals(playerId,key.playerId); }
        @Override public int hashCode() { return Objects.hash(archiveId,playerId); }
    }
}
