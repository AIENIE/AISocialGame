package com.aisocialgame.service.ai.v2;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Request-local model references and seat numbers for public model text. */
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
    private static final Set<String> PUBLIC_ACTIONS = Set.of("SPEAK", "ASK_PLAYER", "ANSWER_PLAYER", "VOTE");
    public static final String PUBLIC_TEXT_REFERENCE = "PUBLIC_TEXT_REFERENCE";
    private static final Pattern TEXT_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])([PE][0-9]+)(?![A-Za-z0-9_])(?:\\h*号)?");
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
        result.put("entityReferenceFormat", "结构化 targetPlayerId 使用合法目标的 P 编号，evidenceEventIds 和记忆中的 eventId 使用可见事件的 E 编号。填写完整引用。E 编号只在当前请求中有效，同一事件在其他请求中的编号可能不同，按实际内容核对说法。");
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
        if (isPublicTextAction(observation.phase(), text(action.get("type")))
                && renderSeats(observation, refs, output, action)) {
            output.put("action", action);
            changes.add("PUBLIC_PLAYER_REFERENCES_RENDERED");
        }
        if("NIGHT_ACTION".equalsIgnoreCase(text(action.get("type")))&&"WITCH_SAVE".equalsIgnoreCase(text(action.get("nightAction")))&&!action.containsKey("useHeal")) {
            action.put("useHeal",true);output.put("action",action);changes.add("WITCH_SAVE_MISSING_USE_HEAL_TRUE");
        }
        return new Decoded(output,List.copyOf(changes));
    }

    public static boolean isPublicTextAction(String phase, String type) {
        String action = text(type).toUpperCase(Locale.ROOT);
        return PUBLIC_ACTIONS.contains(action) || "DAY_VOTE".equals(phase) && "SKIP".equals(action);
    }

    public static boolean containsTextReference(Object value) {
        return value instanceof String prose && TEXT_REFERENCE.matcher(prose).find();
    }

    private static boolean renderSeats(VisibleObservation observation, References refs,
                                       Map<String, Object> output, Map<String, Object> action) {
        Map<String, Integer> seats = new HashMap<>();
        for (var player : observation.players()) {
            if (player.get("seatNumber") instanceof Number seat && seat.intValue() >= 0) {
                seats.put(text(player.get("playerId")), seat.intValue() + 1);
            }
        }
        boolean speechChanged = renderSeats(output, "speech", refs, seats);
        boolean contentChanged = renderSeats(action, "content", refs, seats);
        return speechChanged || contentChanged;
    }

    private static boolean renderSeats(Map<String, Object> fields, String key, References refs, Map<String, Integer> seats) {
        if (!(fields.get(key) instanceof String prose)) return false;
        String rendered = TEXT_REFERENCE.matcher(prose).replaceAll(match -> {
            Integer seat = seats.get(refs.players().get(match.group(1)));
            return Matcher.quoteReplacement(seat == null ? match.group() : seat + "号");
        });
        if (prose.equals(rendered)) return false;
        fields.put(key, rendered);
        return true;
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
