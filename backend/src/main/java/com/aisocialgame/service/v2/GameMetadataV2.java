package com.aisocialgame.service.v2;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.model.Game;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class GameMetadataV2 {
    private final Map<String,GameRuleSet> rules=new LinkedHashMap<>();
    public GameMetadataV2(List<GameRuleSet> definitions) {
        definitions.forEach(r -> { if (rules.put(r.gameId(),r)!=null) throw new IllegalStateException("Duplicate rules metadata"); });
    }
    public List<Game> games() { return rules.values().stream().map(GameRuleSet::definition).toList(); }
    public Optional<Game> find(String id) { return Optional.ofNullable(rules.get(id)).map(GameRuleSet::definition); }

}
