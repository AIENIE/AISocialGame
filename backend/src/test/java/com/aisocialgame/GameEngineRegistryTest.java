package com.aisocialgame;

import com.aisocialgame.engine.GameEngine;
import com.aisocialgame.engine.GameEngineRegistry;
import com.aisocialgame.engine.ValidationResult;
import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class GameEngineRegistryTest {
    @Test
    void registryFindsSupportedEngineAndRejectsUnknownGame() {
        GameEngineRegistry registry = new GameEngineRegistry(List.of(new StubEngine("demo")));

        Assertions.assertTrue(registry.supports("demo"));
        Assertions.assertEquals("demo", registry.require("demo").gameId());
        Assertions.assertThrows(ApiException.class, () -> registry.require("missing"));
    }

    @Test
    void registryRejectsDuplicateGameIds() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> new GameEngineRegistry(List.of(new StubEngine("demo"), new StubEngine("demo"))));
    }

    private record StubEngine(String gameId) implements GameEngine {
        @Override
        public Game metadata() {
            return null;
        }

        @Override
        public ValidationResult validateStart(Room room) {
            return ValidationResult.ok();
        }

        @Override
        public GameStateResponse state(String roomId, User user, String playerIdHeader) {
            return null;
        }

        @Override
        public GameStateResponse start(String roomId, User user, String playerIdHeader) {
            return null;
        }

        @Override
        public GameStateResponse speak(String roomId, SpeakRequest request, User user, String playerIdHeader) {
            return null;
        }

        @Override
        public GameStateResponse vote(String roomId, VoteRequest request, User user, String playerIdHeader) {
            return null;
        }

        @Override
        public GameStateResponse nightAction(String roomId, NightActionRequest request, User user, String playerIdHeader) {
            return null;
        }
    }
}
