package com.aisocialgame.service;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RoomAccessPolicy {
    private final RoomRepository rooms;
    private final GameStateRepository states;

    public RoomAccessPolicy(RoomRepository rooms, GameStateRepository states) {
        this.rooms = rooms;
        this.states = states;
    }

    public void requireRead(String roomId, String viewerId) {
        Room room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        requireRead(room, states.findById(roomId).orElse(null), viewerId);
    }

    public void requireLobbyRead(String roomId, String viewerId) {
        Room room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        requireRead(room, room.getStatus() == com.aisocialgame.model.RoomStatus.WAITING ? null : states.findById(roomId).orElse(null), viewerId);
    }

    public void requireStateSubscription(String roomId, String viewerId) {
        // A new waiting-room member may subscribe before the next game starts.
        // Every delivered state frame still passes the stricter current-game policy.
        try { requireRead(roomId, viewerId); }
        catch (ApiException denied) { requireLobbyRead(roomId, viewerId); }
    }

    public void requireParticipant(String roomId, String viewerId) {
        Room room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        GameState state = room.getStatus() == com.aisocialgame.model.RoomStatus.WAITING ? null : states.findById(roomId).orElse(null);
        if (viewerId == null || !participant(room, state, viewerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅本局参与者可以执行此操作");
        }
    }

    public static void requireRead(Room room, GameState state, String viewerId) {
        if (viewerId == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        boolean startedPrivate = state != null && Boolean.FALSE.equals(com.aisocialgame.engine.v2.RuleSupport.map(state.getData().get("accessSnapshot")).get("publicReplay"));
        if ((room.isPrivate() || startedPrivate) && !participant(room, state, viewerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权访问私密房间");
        }
    }

    public static void snapshot(GameState state, Room room) {
        state.getData().put("accessSnapshot", java.util.Map.of(
                "publicReplay", !room.isPrivate(), "hostUserId", room.getHostUserId() == null ? "" : room.getHostUserId(),
                "participants", state.getPlayers().stream().map(com.aisocialgame.model.GamePlayerState::getPlayerId).toList()));
    }

    private static boolean participant(Room room, GameState state, String viewerId) {
        if (state != null && !state.getPlayers().isEmpty()) {
            Object originalHost = com.aisocialgame.engine.v2.RuleSupport.map(state.getData().get("accessSnapshot")).get("hostUserId");
            return viewerId.equals(originalHost == null ? room.getHostUserId() : originalHost)
                    || state.getPlayers().stream().anyMatch(player -> viewerId.equals(player.getPlayerId()));
        }
        return viewerId.equals(room.getHostUserId()) || room.getSeats().stream().anyMatch(seat -> viewerId.equals(seat.getPlayerId()));
    }
}
