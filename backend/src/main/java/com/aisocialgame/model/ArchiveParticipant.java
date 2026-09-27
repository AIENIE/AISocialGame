package com.aisocialgame.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "archive_participants", indexes = @Index(name = "idx_archive_participant_player", columnList = "player_id,archive_id"))
public class ArchiveParticipant {
    @EmbeddedId private Key id;
    protected ArchiveParticipant() { }
    public ArchiveParticipant(String archiveId, String playerId) { id = new Key(archiveId, playerId); }

    @Embeddable public static class Key implements Serializable {
        @Column(name = "archive_id", length = 96) private String archiveId;
        @Column(name = "player_id", length = 64) private String playerId;
        protected Key() { }
        public Key(String archiveId, String playerId) { this.archiveId = archiveId; this.playerId = playerId; }
        @Override public boolean equals(Object value) { return value instanceof Key other && Objects.equals(archiveId, other.archiveId) && Objects.equals(playerId, other.playerId); }
        @Override public int hashCode() { return Objects.hash(archiveId, playerId); }
    }
}
