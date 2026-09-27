package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.model.GameState;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Deterministic observations about recorded decisions, never an omniscient assessment of hidden truth. */
public final class AiRoundReflection {
    private AiRoundReflection() {}
    public static void decision(Map<String,Object> memory, Map<String,Object> previous, VisibleObservation observation) {
        Map<String,Object> pending=map(memory.get("pendingReflection"));
        pending.put("round",observation.round());
        List<Map<String,Object>> changes=maps(pending.get("judgmentChanges"));
        var before=map(previous.get("beliefs"));
        map(memory.get("beliefs")).forEach((player,value) -> {
            var oldValue=map(before.get(player));var newValue=map(value);oldValue.remove("round");newValue.remove("round");
            if (before.containsKey(player) && !Objects.equals(oldValue,newValue)) {
                var entry=new LinkedHashMap<String,Object>(); entry.put("playerId",player); entry.put("previous",before.get(player)); entry.put("current",value); changes.add(entry);
            }
        });
        pending.put("judgmentChanges",changes.stream().skip(Math.max(0,changes.size()-6)).toList());
        Set<String> evidence=new LinkedHashSet<>();
        for(var change:maps(pending.get("judgmentChanges")))for(String side:List.of("previous","current"))evidence.addAll(strings(map(change.get(side)).get("evidenceEventIds")));
        pending.put("evidenceEventIds",new ArrayList<>(evidence));
        memory.put("pendingReflection",pending);
    }
    public static void closeRounds(GameState state) {
        var all=map(state.getData().get("aiMemoriesV2"));
        for (String actor:new ArrayList<>(all.keySet())) {
            var memory=map(all.get(actor)); var pending=map(memory.get("pendingReflection"));
            if (pending.isEmpty() || (number(pending.get("round"),state.getRoundNumber())>=state.getRoundNumber() && !"SETTLEMENT".equals(state.getPhase()))) continue;
            pending.put("source","SERVER_RECORDED_ACTIONS");pending.put("scope","PRIVATE_TO_ACTOR");
            pending.put("truthAssessment","UNKNOWN");
            pending.put("nextPlan","UNSPECIFIED");
            pending.put("unresolvedHypotheses",maps(memory.get("hypotheses")).stream().limit(6).toList());
            pending.put("commitments",maps(memory.get("commitments")).stream().limit(16).map(c -> Map.of("id",text(c.get("id")),"status",text(c.get("status")),"resolution",map(c.get("resolution")))).toList());
            Set<String> evidence=new LinkedHashSet<>(strings(pending.get("evidenceEventIds")));
            maps(pending.get("unresolvedHypotheses")).forEach(h -> evidence.addAll(strings(h.get("evidenceEventIds"))));
            maps(pending.get("commitments")).forEach(c -> evidence.addAll(strings(map(c.get("resolution")).get("evidenceEventIds"))));
            pending.put("evidenceEventIds",new ArrayList<>(evidence));
            pending.put("confirmedRecordIssues",maps(memory.get("commitments")).stream().filter(c -> "NOT_FULFILLED".equals(c.get("status"))).map(c -> text(c.get("id"))).toList());
            List<Map<String,Object>> rounds=maps(memory.get("roundReflections")); rounds.add(pending);
            memory.put("roundReflections",rounds.stream().skip(Math.max(0,rounds.size()-4)).toList()); memory.remove("pendingReflection");
            memory.put("formatVersion",AiMemoryEntries.FORMAT_VERSION); all.put(actor,memory);
        }
        if (!all.isEmpty()) state.getData().put("aiMemoriesV2",all);
    }
    public static Map<String,Object> difficulty(int level) {
        return switch(level) {
            case 1 -> Map.of("level",1,"name","简单","guide","优先处理一个明确线索，用易懂短句解释选择；不故意犯错或制造事实。","expectations",List.of("一个具体依据","清楚回应当前问题"));
            case 3 -> Map.of("level",3,"name","进阶","guide","比较可见证据与反证，考虑替代假说和规则内风险；不能获得额外信息。","expectations",List.of("比较假说","根据反证解释改判"));
            default -> Map.of("level",2,"name","娱乐","guide","兼顾参与节奏与可检验试探，给其他玩家交流空间；保留所选人格。","expectations",List.of("回应同伴","试探有明确对象"));
        };
    }
}
