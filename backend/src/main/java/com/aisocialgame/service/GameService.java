package com.aisocialgame.service;

import com.aisocialgame.engine.GameEngineRegistry;
import com.aisocialgame.model.Game;
import com.aisocialgame.repository.GameRepository;
import com.aisocialgame.repository.RoomRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class GameService {
    private final GameRepository gameRepository;
    private final RoomRepository roomRepository;
    private final ObjectProvider<GameEngineRegistry> engineRegistryProvider;

    public GameService(GameRepository gameRepository,
                       RoomRepository roomRepository,
                       ObjectProvider<GameEngineRegistry> engineRegistryProvider) {
        this.gameRepository = gameRepository;
        this.roomRepository = roomRepository;
        this.engineRegistryProvider = engineRegistryProvider;
    }

    public List<Game> listGames() {
        return gameRepository.findAll().stream().map(this::attachOnlineCount).toList();
    }

    public Optional<Game> findById(String id) {
        return gameRepository.findById(id).map(this::attachOnlineCount);
    }

    private Game attachOnlineCount(Game game) {
        int online = roomRepository.findByGameIdOrderByCreatedAtAsc(game.getId()).stream()
                .mapToInt(room -> room.getSeats() != null ? room.getSeats().size() : 0)
                .sum();
        game.setOnlineCount(online);
        GameEngineRegistry registry = engineRegistryProvider.getIfAvailable();
        if (registry != null && registry.supports(game.getId())) {
            var engine = registry.require(game.getId());
            game.setEngineBacked(true);
            game.setPhaseDefinitions(engine.phaseDefinitions());
            game.setRoleDefinitions(engine.roleDefinitions());
        } else {
            game.setEngineBacked(false);
        }
        return game;
    }
}
