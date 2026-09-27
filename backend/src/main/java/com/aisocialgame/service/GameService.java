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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aisocialgame.service.v2.GameMetadataV2 metadataV2;

    public GameService(GameRepository gameRepository,
                       RoomRepository roomRepository,
                       ObjectProvider<GameEngineRegistry> engineRegistryProvider) {
        this.gameRepository = gameRepository;
        this.roomRepository = roomRepository;
        this.engineRegistryProvider = engineRegistryProvider;
    }

    public List<Game> listGames() {
        return (metadataV2!=null && !metadataV2.games().isEmpty()?metadataV2.games():gameRepository.findAll()).stream().map(this::attachOnlineCount).toList();
    }

    public Optional<Game> findById(String id) {
        return (metadataV2==null?gameRepository.findById(id):metadataV2.find(id).or(() -> gameRepository.findById(id))).map(this::attachOnlineCount);
    }

    private Game attachOnlineCount(Game game) {
        int online = Math.toIntExact(Math.min(Integer.MAX_VALUE, roomRepository.sumSeatCountByGameId(game.getId())));
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
        return metadataV2 == null ? game : metadataV2.enhance(game);
    }
}
