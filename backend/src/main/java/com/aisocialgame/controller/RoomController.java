package com.aisocialgame.controller;

import com.aisocialgame.dto.AddAiRequest;
import com.aisocialgame.dto.CreateRoomRequest;
import com.aisocialgame.dto.JoinRoomRequest;
import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.RoomResponse;
import com.aisocialgame.dto.JoinRoomResult;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api/games/{gameId}/rooms")
public class RoomController {

    private final RoomService roomService;
    private final com.aisocialgame.service.RoomAccessPolicy access;

    public RoomController(RoomService roomService, com.aisocialgame.service.RoomAccessPolicy access) {
        this.access = access;
        this.roomService = roomService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<RoomResponse>> listRooms(@PathVariable("gameId") String gameId,
                                                                 @RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "30") int size,
                                                                 @RequestParam(required = false) String status, @CurrentUser User viewer) {
        RoomStatus roomStatus = parseStatus(status);
        Page<Room> rooms = roomService.listByGame(gameId, roomStatus, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                rooms.getContent().stream().map(RoomResponse::new).toList(),
                Math.max(1, page),
                Math.min(Math.max(1, size), 100),
                rooms.getTotalElements()
        ));
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(@PathVariable("gameId") String gameId,
                                                   @Valid @RequestBody CreateRoomRequest request,
                                                   @CurrentUser User user) {
        Room room = roomService.createRoom(gameId, request.getRoomName(), request.getIsPrivate(), request.getPassword(), request.getCommMode(), request.getConfig(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoomResponse(room));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> roomDetail(@PathVariable("gameId") String gameId, @PathVariable("roomId") String roomId, @CurrentUser User user) {
        roomService.entry(gameId, roomId);
        access.requireLobbyRead(roomId, user.getId());
        Room room = roomService.getRoom(roomId);
        return ResponseEntity.ok(new RoomResponse(room));
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomResponse> joinRoom(@PathVariable("gameId") String gameId, @PathVariable("roomId") String roomId,
                                                 @Valid @RequestBody JoinRoomRequest request,
                                                 @CurrentUser User user) {
        roomService.entry(gameId, roomId);
        String displayName = user.getNickname();
        JoinRoomResult result = roomService.joinRoom(roomId, displayName, user, request.getPassword());
        return ResponseEntity.ok(new RoomResponse(result.getRoom(), result.getSeat().getPlayerId()));
    }

    @PostMapping("/{roomId}/ai")
    public ResponseEntity<RoomResponse> addAi(@PathVariable("gameId") String gameId, @PathVariable("roomId") String roomId,
                                              @Valid @RequestBody AddAiRequest request,
                                              @CurrentUser User user) {
        roomService.entry(gameId, roomId);
        Room room = roomService.addAi(roomId, request.getPersonaId(), user);
        return ResponseEntity.ok(new RoomResponse(room));
    }

    @GetMapping("/{roomId}/entry")
    public com.aisocialgame.dto.RoomEntryResponse entry(@PathVariable String gameId, @PathVariable String roomId, @CurrentUser User user) {
        return new com.aisocialgame.dto.RoomEntryResponse(roomService.entry(gameId, roomId), user.getId());
    }

    private RoomStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RoomStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "房间状态不合法");
        }
    }
}
