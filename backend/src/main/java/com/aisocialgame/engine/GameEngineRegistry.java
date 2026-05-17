package com.aisocialgame.engine;

import com.aisocialgame.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GameEngineRegistry {
    private final Map<String, GameEngine> engines = new LinkedHashMap<>();

    public GameEngineRegistry(List<GameEngine> engineList) {
        for (GameEngine engine : engineList) {
            GameEngine existing = engines.putIfAbsent(engine.gameId(), engine);
            if (existing != null) {
                throw new IllegalStateException("Duplicate game engine: " + engine.gameId());
            }
        }
    }

    public GameEngine require(String gameId) {
        GameEngine engine = engines.get(gameId);
        if (engine == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂不支持的游戏");
        }
        return engine;
    }

    public boolean supports(String gameId) {
        return engines.containsKey(gameId);
    }

    public List<GameEngine> list() {
        return List.copyOf(engines.values());
    }
}
