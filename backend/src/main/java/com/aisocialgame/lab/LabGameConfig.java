package com.aisocialgame.lab;

import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Game-specific configuration stays explicit; old experiments retain their original semantics. */
public final class LabGameConfig {
    private LabGameConfig() {}
    public static String gameId(Map<String,Object> configuration) {
        return text(configuration.getOrDefault("gameId","undercover"));
    }
    public static boolean werewolf(Map<String,Object> configuration) { return "werewolf".equals(gameId(configuration)); }
    public static Map<String,Object> werewolfRules(int playerCount,Map<String,Object> supplied) {
        require(Set.of(6,9).contains(playerCount),"狼人杀实验首期支持 6 或 9 人标准板");
        var rules=new LinkedHashMap<String,Object>(Map.of("template","standard","playerCount",playerCount,
                "witchRule","first_night","winCondition","side","speechTime",60,"hasLastWords","first_night","aiDifficulty",2));
        if(supplied!=null) {
            require(Set.of("template","playerCount","witchRule","winCondition","speechTime","hasLastWords","aiDifficulty").containsAll(supplied.keySet()),"狼人杀规则包含不支持的字段");
            rules.putAll(supplied);
        }
        require("standard".equals(rules.get("template")) && number(rules.get("playerCount"),0)==playerCount,"人数和标准板配置不一致");
        require(Set.of("first_night","no_save","always_save").contains(text(rules.get("witchRule"))),"女巫规则无效");
        require(Set.of("side","city").contains(text(rules.get("winCondition"))),"胜负规则无效");
        require(Set.of("first_night","always","none").contains(text(rules.get("hasLastWords"))),"遗言规则无效");
        require(Set.of(60,90,120).contains(number(rules.get("speechTime"),0)),"发言时间无效");
        return rules;
    }
    /** Domain-separated seeds avoid identical seat/role permutations binding people to roles. */
    public static long seed(long root,String domain) {
        long z=root ^ ((long)domain.hashCode()<<32) ^ domain.length();
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
        z=(z^(z>>>27))*0x94d049bb133111ebL;
        return z^(z>>>31);
    }
}
