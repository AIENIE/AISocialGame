package com.aisocialgame.controller;

import com.aisocialgame.dto.AddAiRequest;
import com.aisocialgame.dto.CreateRoomRequest;
import com.aisocialgame.dto.JoinRoomRequest;
import com.aisocialgame.dto.RoomResponse;
import com.aisocialgame.dto.JoinRoomResult;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/games/{gameId}/rooms")
public class RoomController {

    private final RoomService roomService;
    private final AuthService authService;

    public RoomController(RoomService roomService, AuthService authService) {
        this.roomService = roomService;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> listRooms(@PathVariable("gameId") String gameId) {
        List<RoomResponse> rooms = roomService.listByGame(gameId).stream().map(RoomResponse::new).toList();
        return ResponseEntity.ok(rooms);
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(@PathVariable("gameId") String gameId,
                                                   @Valid @RequestBody CreateRoomRequest request,
                                                   @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        Room room = roomService.createRoom(gameId, request.getRoomName(), request.getIsPrivate(), request.getPassword(), request.getCommMode(), request.getConfig(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoomResponse(room));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> roomDetail(@PathVariable("roomId") String roomId) {
        Room room = roomService.getRoom(roomId);
        return ResponseEntity.ok(new RoomResponse(room));
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomResponse> joinRoom(@PathVariable("roomId") String roomId,
                                                 @Valid @RequestBody JoinRoomRequest request,
                                                 @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        User user = requireUser(token);
        String displayName = user.getNickname();
        JoinRoomResult result = roomService.joinRoom(roomId, displayName, user, request.getPassword());
        return ResponseEntity.ok(new RoomResponse(result.getRoom(), result.getSeat().getPlayerId()));
    }

    @PostMapping("/{roomId}/ai")
    public ResponseEntity<RoomResponse> addAi(@PathVariable("roomId") String roomId,
                                              @Valid @RequestBody AddAiRequest request,
                                              @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        Room room = roomService.addAi(roomId, request.getPersonaId(), requireUser(token));
        return ResponseEntity.ok(new RoomResponse(room));
    }

    private User requireUser(String token) {
        User user = authService.authenticate(token);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
