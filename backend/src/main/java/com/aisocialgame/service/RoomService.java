package com.aisocialgame.service;

import com.aisocialgame.dto.JoinRoomResult;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.Persona;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.PersonaRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.dto.ws.SeatEvent;
import com.aisocialgame.websocket.GamePushService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class RoomService {
    private static final int MIN_PRIVATE_ROOM_PASSWORD_LENGTH = 4;
    private static final int MAX_PRIVATE_ROOM_PASSWORD_LENGTH = 64;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RoomRepository roomRepository;
    private final GameService gameService;
    private final PersonaRepository personaRepository;
    private final AiNameService aiNameService;
    private final GamePushService gamePushService;
    private final WriteRateLimiter limiter;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final org.springframework.transaction.support.TransactionTemplate joinTransaction;
    @org.springframework.beans.factory.annotation.Value("${app.room.password-attempts-per-minute:5}")
    private int passwordAttempts = 5;
    @org.springframework.beans.factory.annotation.Value("${app.room.join-attempts-per-minute:30}")
    private int globalJoinAttempts = 30;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aisocialgame.service.safety.AiSafetyService safetyService;

    public RoomService(RoomRepository roomRepository,
                       GameService gameService,
                       PersonaRepository personaRepository,
                       AiNameService aiNameService,
                       GamePushService gamePushService, WriteRateLimiter limiter,
                       org.springframework.transaction.PlatformTransactionManager manager) {
        this.limiter = limiter;
        this.joinTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.roomRepository = roomRepository;
        this.gameService = gameService;
        this.personaRepository = personaRepository;
        this.aiNameService = aiNameService;
        this.gamePushService = gamePushService;
    }

    public Room createRoom(String gameId, String name, boolean isPrivate, String password, String commMode, Map<String, Object> config, User creator) {
        Game game = gameService.findById(gameId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "游戏不存在"));
        int maxPlayers = normalizeMaxPlayers(gameId, resolveMaxPlayers(config, game.getMaxPlayers()), game.getMaxPlayers());
        String storedPassword = null;
        if (isPrivate) {
            storedPassword = encodePrivateRoomPassword(password);
        }

        Map<String, Object> safeConfig = config == null ? new HashMap<>() : new HashMap<>(config);
        safeConfig.put("playerCount",maxPlayers);
        var validation=com.aisocialgame.engine.v2.GameConfiguration.validate(game,safeConfig);
        if (!validation.valid()) throw new ApiException(HttpStatus.BAD_REQUEST,validation.message());
        safeConfig.remove("usedWordPairIds");
        boolean authorHost = "undercover".equals(gameId) && "custom".equals(safeConfig.get("wordPack"));
        Object customWords = safeConfig.remove("customWords");
        Room room = new Room(UUID.randomUUID().toString(), gameId, name, RoomStatus.WAITING, maxPlayers, isPrivate, storedPassword, commMode, safeConfig);
        if (creator != null) room.setHostUserId(creator.getId());
        if (authorHost) {
            if (creator == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
            var words = validateCustomWords(customWords, creator);
            room.getPrivateConfig().put("customWords", words);
            safeConfig.put("customWordCount", words.size()); safeConfig.put("hostMode", "AUTHOR");
        }

        // Auto seat creator as host
        if (creator != null && !authorHost) {
            RoomSeat host = new RoomSeat(0, creator.getId(), creator.getNickname(), false, null, creator.getAvatar(), true, true);
            room.getSeats().add(host);
        }

        return roomRepository.save(room);
    }

    @Transactional(readOnly = true)
    public List<Room> listByGame(String gameId) {
        return roomRepository.findByGameIdOrderByCreatedAtAsc(gameId);
    }

    @Transactional(readOnly = true)
    public Page<Room> listByGame(String gameId, RoomStatus status, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        PageRequest pageable = PageRequest.of(normalizedPage - 1, normalizedSize);
        if (status == null) {
            return roomRepository.findByGameIdOrderByCreatedAtDesc(gameId, pageable);
        }
        return roomRepository.findByGameIdAndStatusOrderByCreatedAtDesc(gameId, status, pageable);
    }

    @Transactional(readOnly = true)
    public Room getRoom(String roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public JoinRoomResult joinRoom(String roomId, String displayName, User user, String password) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        limiter.require(user.getId(), List.of(new WriteRateLimiter.Limit("join-global", globalJoinAttempts)));
        var snapshot = roomRepository.findJoinSnapshot(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        limiter.require(user.getId(), List.of(new WriteRateLimiter.Limit("join-room:" + snapshot.getId(), passwordAttempts)));
        if (snapshot.getPrivateRoom()) {
            String normalized = password == null ? "" : password.trim();
            if (!StringUtils.hasText(snapshot.getPassword()) || normalized.isBlank() || normalized.length() > MAX_PRIVATE_ROOM_PASSWORD_LENGTH
                    || !passwordEncoder.matches(normalized, snapshot.getPassword())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "私密房间密码错误", "ROOM_PASSWORD_INVALID", Map.of());
            }
        }
        return joinTransaction.execute(status -> joinVerified(snapshot.getId(), displayName, user, snapshot));
    }

    private JoinRoomResult joinVerified(String roomId, String displayName, User user, RoomRepository.JoinSnapshot snapshot) {
        Room room = getRoomForUpdate(roomId);
        entityManager.refresh(room);
        if (room.isPrivate() != snapshot.getPrivateRoom() || !java.util.Objects.equals(room.getPassword(), snapshot.getPassword())) {
            throw new ApiException(HttpStatus.CONFLICT, "房间口令已更新，请重试", "ROOM_PASSWORD_CHANGED", Map.of());
        }
        if ("AUTHOR".equals(room.getConfig().get("hostMode")) && user.getId().equals(room.getHostUserId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "出题主持者不能加入玩家席位，可在房间中组织和观战");
        }
        if (room.getStatus() == RoomStatus.PLAYING && room.getSeats().stream().noneMatch(s -> user.getId().equals(s.getPlayerId()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "对局已开始，不能加入玩家席位");
        }

        // Already joined
        String userId = user.getId();
        if (userId != null) {
            RoomSeat seat = room.getSeats().stream().filter(s -> userId.equals(s.getPlayerId())).findFirst().orElse(null);
            if (seat != null) {
                return new JoinRoomResult(room, seat);
            }
        }

        if (room.getSeats().size() >= room.getMaxPlayers()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "房间已满", "ROOM_FULL", Map.of());
        }

        int seatNumber = room.getSeats().size();
        String avatar = user != null ? user.getAvatar() : "https://api.dicebear.com/7.x/avataaars/svg?seed=" + displayName.replace(" ", "");
        String playerId = userId != null ? userId : UUID.randomUUID().toString();
        RoomSeat seat = new RoomSeat(seatNumber, playerId, displayName, false, null, avatar, true, room.getHostUserId() == null && room.getSeats().isEmpty());
        room.getSeats().add(seat);
        room.syncSeatCount();
        roomRepository.save(room);
        gamePushService.pushSeatChange(roomId, new SeatEvent("JOIN", seat));
        return new JoinRoomResult(room, seat);
    }

    public Room addAi(String roomId, String personaId, User actor) {
        Room room = getRoomForUpdate(roomId);
        requireHost(room, actor);
        if (room.getStatus() != RoomStatus.WAITING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只有等待中的房间可以添加 AI");
        }
        if (room.getSeats().size() >= room.getMaxPlayers()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "房间已满", "ROOM_FULL", Map.of());
        }
        Persona persona = personaRepository.findById(personaId);
        if (persona == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI人设不存在");
        }
        int seatNumber = room.getSeats().size();
        String aiDisplayName = aiNameService.localName(persona);
        RoomSeat seat = new RoomSeat(seatNumber, "ai-" + personaId + "-" + seatNumber, aiDisplayName, true, personaId, persona.getAvatar(), true, false);
        room.getSeats().add(seat);
        room.syncSeatCount();
        roomRepository.save(room);
        gamePushService.pushSeatChange(roomId, new SeatEvent("AI_ADDED", seat));
        return room;
    }

    private Room getRoomForUpdate(String roomId) {
        return roomRepository.findByIdForUpdate(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
    }

    private String encodePrivateRoomPassword(String password) {
        String normalized = password == null ? "" : password.trim();
        if (normalized.length() < MIN_PRIVATE_ROOM_PASSWORD_LENGTH || normalized.length() > MAX_PRIVATE_ROOM_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "私密房间密码长度需为 4-64 位");
        }
        return passwordEncoder.encode(normalized);
    }


    private void requireHost(Room room, User actor) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        boolean host = actor.getId().equals(room.getHostUserId()) || room.getSeats().stream()
                .anyMatch(seat -> actor.getId().equals(seat.getPlayerId()) && seat.isHost());
        if (!host) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有房主可以添加 AI");
        }
    }

    public void updateStatus(String roomId, RoomStatus status) {
        Room room = getRoom(roomId);
        room.setStatus(status);
        roomRepository.save(room);
    }

    private List<Map<String, Object>> validateCustomWords(Object raw, User creator) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "自定义词库需包含1-100组词对");
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> row = com.aisocialgame.engine.v2.RuleSupport.map(list.get(i));
            String a = normalizeWord(row.get("wordA")), b = normalizeWord(row.get("wordB"));
            if (a.codePointCount(0, a.length()) < 2 || a.codePointCount(0, a.length()) > 20 || b.codePointCount(0, b.length()) < 2 || b.codePointCount(0, b.length()) > 20 || a.equalsIgnoreCase(b)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "第" + (i + 1) + "行词语需为2-20字，且两个词不能相同");
            }
            String key = java.util.stream.Stream.of(a.toLowerCase(java.util.Locale.ROOT), b.toLowerCase(java.util.Locale.ROOT)).sorted().collect(java.util.stream.Collectors.joining("\u0000"));
            if (!seen.add(key)) throw new ApiException(HttpStatus.BAD_REQUEST, "第" + (i + 1) + "行词对重复");
            if (safetyService != null) {
                var context = com.aisocialgame.service.safety.AiSafetyContext.source(com.aisocialgame.service.safety.AiSafetyService.SOURCE_GAME_SPEECH).room(null, "undercover").user(creator.getId(), creator.getId());
                safetyService.requireAllowedInput(a, context); safetyService.requireAllowedInput(b, context);
            }
            result.add(Map.of("wordA", a, "wordB", b));
        }
        return result;
    }
    private String normalizeWord(Object value) {
        return java.text.Normalizer.normalize(value == null ? "" : value.toString().strip(), java.text.Normalizer.Form.NFKC);
    }

    private int resolveMaxPlayers(Map<String, Object> config, int fallback) {
        if (config == null) return fallback;
        Object value = config.get("playerCount");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private int normalizeMaxPlayers(String gameId, int requested, int gameMaxPlayers) {
        int minRequired = minimumPlayersForGame(gameId);
        int normalized = Math.max(requested, minRequired);
        if (gameMaxPlayers > 0) {
            normalized = Math.min(normalized, gameMaxPlayers);
        }
        return normalized;
    }

    private int minimumPlayersForGame(String gameId) {
        return gameService.findById(gameId).map(Game::getMinPlayers).orElse(2);
    }
}
