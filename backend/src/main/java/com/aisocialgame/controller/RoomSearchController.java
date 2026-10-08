package com.aisocialgame.controller;

import com.aisocialgame.dto.RoomEntryResponse;
import com.aisocialgame.model.User;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.web.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomSearchController {
    private final RoomService rooms;
    public RoomSearchController(RoomService rooms) { this.rooms = rooms; }

    @GetMapping("/search")
    public RoomEntryResponse search(@RequestParam String roomCode, @CurrentUser User user) {
        return new RoomEntryResponse(rooms.search(roomCode, user), user.getId());
    }
}
