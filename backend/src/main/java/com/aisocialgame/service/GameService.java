package com.aisocialgame.service;

import com.aisocialgame.model.Game;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.v2.GameMetadataV2;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class GameService {
    private final RoomRepository rooms;
    private final GameMetadataV2 metadata;
    public GameService(RoomRepository rooms, GameMetadataV2 metadata) {
        this.rooms=rooms; this.metadata=metadata;
    }
    public List<Game> listGames() { return metadata.games().stream().map(this::attachOnlineCount).toList(); }
    public Optional<Game> findById(String id) { return metadata.find(id).map(this::attachOnlineCount); }
    private Game attachOnlineCount(Game game) {
        game.setOnlineCount(Math.toIntExact(Math.min(Integer.MAX_VALUE,rooms.sumSeatCountByGameId(game.getId()))));
        return game;
    }
}
