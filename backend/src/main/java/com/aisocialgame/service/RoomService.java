package com.aisocialgame.service;

import com.aisocialgame.dto.JoinRoomResult;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameStatus;
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

    public RoomService(RoomRepository roomRepository,
                       GameService gameService,
                       PersonaRepository personaRepository,
                       AiNameService aiNameService,
                       GamePushService gamePushService) {
        this.roomRepository = roomRepository;
        this.gameService = gameService;
        this.personaRepository = personaRepository;
        this.aiNameService = aiNameService;
        this.gamePushService = gamePushService;
    }

    public Room createRoom(String gameId, String name, boolean isPrivate, String password, String commMode, Map<String, Object> config, User creator) {
        Game game = gameService.findById(gameId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "游戏不存在"));
        if (game.getStatus() != GameStatus.ACTIVE || !game.isEngineBacked()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "游戏暂未开放");
        }
        int maxPlayers = normalizeMaxPlayers(gameId, resolveMaxPlayers(config, game.getMaxPlayers()), game.getMaxPlayers());
        String storedPassword = null;
        if (isPrivate) {
            storedPassword = encodePrivateRoomPassword(password);
        }

        Room room = new Room(UUID.randomUUID().toString(), gameId, name, RoomStatus.WAITING, maxPlayers, isPrivate, storedPassword, commMode, config != null ? config : new HashMap<>());

        // Auto seat creator as host
        if (creator != null) {
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

    public JoinRoomResult joinRoom(String roomId, String displayName, User user, String password) {
        Room room = getRoomForUpdate(roomId);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        validatePrivateRoomPassword(room, password);

        // Already joined
        String userId = user.getId();
        if (userId != null) {
            RoomSeat seat = room.getSeats().stream().filter(s -> userId.equals(s.getPlayerId())).findFirst().orElse(null);
            if (seat != null) {
                return new JoinRoomResult(room, seat);
            }
        }

        if (room.getSeats().size() >= room.getMaxPlayers()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "房间已满");
        }

        int seatNumber = room.getSeats().size();
        String avatar = user != null ? user.getAvatar() : "https://api.dicebear.com/7.x/avataaars/svg?seed=" + displayName.replace(" ", "");
        String playerId = userId != null ? userId : UUID.randomUUID().toString();
        RoomSeat seat = new RoomSeat(seatNumber, playerId, displayName, false, null, avatar, true, room.getSeats().isEmpty());
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "房间已满");
        }
        Persona persona = personaRepository.findById(personaId);
        if (persona == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI人设不存在");
        }
        int seatNumber = room.getSeats().size();
        String aiDisplayName = aiNameService.generateName(persona);
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

    private void validatePrivateRoomPassword(Room room, String password) {
        if (!room.isPrivate()) {
            return;
        }
        String stored = room.getPassword();
        if (!StringUtils.hasText(stored)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "私密房间密码未配置");
        }
        String normalized = password == null ? "" : password.trim();
        if (!StringUtils.hasText(normalized) || !passwordEncoder.matches(normalized, stored)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "私密房间密码错误");
        }
    }

    private void requireHost(Room room, User actor) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        boolean host = room.getSeats().stream()
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
        if ("undercover".equals(gameId)) {
            return 4;
        }
        if ("werewolf".equals(gameId)) {
            return 6;
        }
        if ("turtle_soup".equals(gameId)) {
            return 1;
        }
        return 2;
    }
}
