package com.aisocialgame.service.ai.v2;

import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Request-local model references. Never changes speech, game permissions or persisted observations. */
public final class WerewolfAiProtocol {
    private WerewolfAiProtocol() {}
    public static String implementationHash() {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(Class<?> type:List.of(WerewolfAiProtocol.class,AiPromptProjection.class,AiTurnGenerator.class,com.aisocialgame.lab.LabTrainingInput.class))
                try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){if(in==null)throw new IllegalStateException("Missing protocol class");digest.update(in.readAllBytes());}
            try(var in=WerewolfAiProtocol.class.getResourceAsStream("/game-knowledge/social-player-system.txt")){digest.update(Objects.requireNonNull(in).readAllBytes());}
            digest.update(com.aisocialgame.lab.LabTrainingTeaching.JUDGE_SYSTEM.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot freeze evaluation protocol",failure);}
    }
    private static final Set<String> PLAYER_FIELDS=Set.of("playerId","actorId","targetId","targetPlayerId");
    private static final Set<String> EVENT_FIELDS=Set.of("eventId","questionEventId","sourceEventId");
    private static final Set<String> TEXT_FIELDS=Set.of("content","message","speech","text","hypothesis","sourceQuote","advice","condition","exceptions","statement","reason","summary");
    public record References(Map<String,String> players,Map<String,String> events) {
        public Map<String,Object> snapshot() { return Map.of("players",players,"events",events); }
    }
    public record Decoded(Map<String,Object> output,List<String> normalizations) {}
    public static References references(VisibleObservation o) {
        var players=new LinkedHashMap<String,String>();var events=new LinkedHashMap<String,String>();
        o.players().stream().sorted(Comparator.comparingInt(p->number(p.get("seatNumber"),Integer.MAX_VALUE)))
                .forEach(p->{String id=text(p.get("playerId"));if(!id.isBlank()&&!players.containsValue(id))players.put("P"+(players.size()+1),id);});
        o.events().forEach(e->{String id=text(e.get("eventId"));if(!id.isBlank()&&!events.containsValue(id))events.put("E"+(events.size()+1),id);});
        return new References(Collections.unmodifiableMap(players),Collections.unmodifiableMap(events));
    }
    public static References restore(Map<String,Object> snapshot) {
        return new References(stringsMap(snapshot.get("players")),stringsMap(snapshot.get("events")));
    }
    private static Map<String,String> stringsMap(Object source) {
        var result=new LinkedHashMap<String,String>();map(source).forEach((k,v)->result.put(k,text(v)));return Collections.unmodifiableMap(result);
    }
    public static Map<String,Object> encode(Map<String,Object> payload,References refs) {
        var reverse=new HashMap<String,String>();refs.players.forEach((a,id)->reverse.put(id,a));refs.events.forEach((a,id)->reverse.put(id,a));
        var result=map(encodeValue(payload,"",reverse));
        result.put("entityReferenceFormat","P1… are player references in the seat table; E1… are visible event references. Copy complete references exactly. targetPlayerId uses P references; evidenceEventIds and memory eventId use E references. Never use dictionary indices, seat numbers, shortened IDs or player references as evidence. Speech and historical quotes are unchanged.");
        return result;
    }
    private static Object encodeValue(Object value,String field,Map<String,String> reverse) {
        if(TEXT_FIELDS.contains(field))return value;
        if(value instanceof String text) {
            if("$ref".equals(field)&&text.startsWith("/"))return Arrays.stream(text.substring(1).split("/",-1))
                    .map(p->p.replace("~1","/").replace("~0","~")).map(p->reverse.getOrDefault(p,p)).map(p->p.replace("~","~0").replace("/","~1")).reduce("",(a,b)->a+"/"+b);
            return reverse.getOrDefault(text,text);
        }
        if(value instanceof List<?> list)return list.stream().map(v->encodeValue(v,field,reverse)).toList();
        if(value instanceof Map<?,?> source) {
            var out=new LinkedHashMap<String,Object>();source.forEach((k,v)->out.put(reverse.getOrDefault(k.toString(),k.toString()),encodeValue(v,k.toString(),reverse)));return out;
        }
        return value;
    }
    public static Decoded decode(VisibleObservation observation,Map<String,Object> raw,References refs) {
        if(!"werewolf".equals(observation.gameId()))return new Decoded(raw,List.of());
        var output=map(decodeValue(raw,"",refs));var action=new LinkedHashMap<>(map(output.get("action")));var changes=new ArrayList<String>();
        if("NIGHT_ACTION".equalsIgnoreCase(text(action.get("type")))&&"WITCH_SAVE".equalsIgnoreCase(text(action.get("nightAction")))&&!action.containsKey("useHeal")) {
            action.put("useHeal",true);output.put("action",action);changes.add("WITCH_SAVE_MISSING_USE_HEAL_TRUE");
        }
        return new Decoded(output,List.copyOf(changes));
    }
    private static Object decodeValue(Object value,String field,References refs) {
        if(TEXT_FIELDS.contains(field))return value;
        if(value instanceof String id) {
            if(PLAYER_FIELDS.contains(field)||"targets".equals(field))return resolve(id,refs.players,refs.events,"INVALID_PLAYER_REFERENCE");
            if(EVENT_FIELDS.contains(field)||"evidenceEventIds".equals(field))return resolve(id,refs.events,refs.players,"INVALID_EVENT_REFERENCE");
            return value;
        }
        if(value instanceof List<?> list)return list.stream().map(v->decodeValue(v,field,refs)).toList();
        if(value instanceof Map<?,?> source) {
            var result=new LinkedHashMap<String,Object>();source.forEach((k,v)->result.put(k.toString(),decodeValue(v,k.toString(),refs)));return result;
        }
        return value;
    }
    private static String resolve(String id,Map<String,String> expected,Map<String,String> other,String code) {
        if(expected.containsKey(id))return expected.get(id);
        if(other.containsKey(id)||id.matches("[PE][0-9]+"))throw new InvalidReference(code);
        return id; // Canonical IDs remain compatible; the normal visibility/target validators reject unknown IDs.
    }
    public static final class InvalidReference extends IllegalArgumentException {
        public final String code;
        InvalidReference(String code) {super(code);this.code=code;}
    }
}
