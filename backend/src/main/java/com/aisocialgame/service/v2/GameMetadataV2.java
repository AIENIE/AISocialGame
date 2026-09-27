package com.aisocialgame.service.v2;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.model.Game;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class GameMetadataV2 {
    private final Map<String,GameRuleSet> rules=new LinkedHashMap<>();
    private final boolean enabled;
    public GameMetadataV2(List<GameRuleSet> definitions,@Value("${app.game.v2-enabled:true}") boolean enabled) {
        definitions.forEach(r -> { if (rules.put(r.gameId(),r)!=null) throw new IllegalStateException("Duplicate rules metadata"); }); this.enabled=enabled;
    }
    public List<Game> games() { return enabled?rules.values().stream().map(GameRuleSet::definition).toList():List.of(); }
    public Optional<Game> find(String id) { return enabled&&rules.containsKey(id)?Optional.of(rules.get(id).definition()):Optional.empty(); }
    public Game enhance(Game original) { Game result=find(original.getId()).orElse(original); result.setOnlineCount(original.getOnlineCount()); return result; }
}
