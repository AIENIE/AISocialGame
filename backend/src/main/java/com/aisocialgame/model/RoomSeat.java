package com.aisocialgame.model;

public class RoomSeat {
    private int seatNumber;
    private String playerId;
    private String displayName;
    private boolean ai;
    private String personaId;
    private String avatar;
    private boolean ready;
    private boolean host;

    public RoomSeat() {}

    public RoomSeat(int seatNumber, String playerId, String displayName, boolean ai, String personaId, String avatar, boolean ready, boolean host) {
        this.seatNumber = seatNumber;
        this.playerId = playerId;
        this.displayName = displayName;
        this.ai = ai;
        this.personaId = personaId;
        this.avatar = avatar;
        this.ready = ready;
        this.host = host;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAi() {
        return ai;
    }

    public String getPersonaId() {
        return personaId;
    }

    public String getAvatar() {
        return avatar;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isHost() {
        return host;
    }

    public void setSeatNumber(int seatNumber) {
        this.seatNumber = seatNumber;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setAi(boolean ai) {
        this.ai = ai;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public void setHost(boolean host) {
        this.host = host;
    }
    // JSON-converted list values need structural equality for Hibernate dirty checking.
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RoomSeat value)) return false;
        return java.util.Objects.equals(seatNumber, value.seatNumber)
                && java.util.Objects.equals(playerId, value.playerId)
                && java.util.Objects.equals(displayName, value.displayName)
                && java.util.Objects.equals(ai, value.ai)
                && java.util.Objects.equals(personaId, value.personaId)
                && java.util.Objects.equals(avatar, value.avatar)
                && java.util.Objects.equals(ready, value.ready)
                && java.util.Objects.equals(host, value.host);
    }
    @Override public int hashCode() { return java.util.Objects.hash(seatNumber, playerId, displayName, ai, personaId, avatar, ready, host); }

}
