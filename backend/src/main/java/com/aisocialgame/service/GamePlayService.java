package com.aisocialgame.service;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.engine.GameEngine;
import com.aisocialgame.engine.GameEngineRegistry;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GamePlayService {
    private final GameEngineRegistry engineRegistry;
    private final RoomService roomService;

    public GamePlayService(GameEngineRegistry engineRegistry, RoomService roomService) {
        this.engineRegistry = engineRegistry;
        this.roomService = roomService;
    }

    @Transactional
    public GameStateResponse state(String gameId, String roomId, User user) {
        return engine(gameId).state(roomId, user);
    }

    public GameStateResponse start(String gameId, String roomId, User user) {
        Room room = roomService.getRoom(roomId);
        var validation = engine(gameId).validateStart(room);
        if (!validation.valid()) {
            throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, validation.message());
        }
        return engine(gameId).start(roomId, user);
    }

    public GameStateResponse speak(String gameId, String roomId, SpeakRequest request, User user) {
        return engine(gameId).speak(roomId, request, user);
    }

    public GameStateResponse vote(String gameId, String roomId, VoteRequest request, User user) {
        return engine(gameId).vote(roomId, request, user);
    }

    public GameStateResponse nightAction(String gameId, String roomId, NightActionRequest request, User user) {
        return engine(gameId).nightAction(roomId, request, user);
    }

    public GameStateResponse action(String gameId, String roomId, PlayerAction action, User user) {
        return engine(gameId).action(roomId, action, user);
    }

    private GameEngine engine(String gameId) {
        return engineRegistry.require(gameId);
    }
}
