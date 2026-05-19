package com.aisocialgame;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.exception.ApiException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class RoomServiceTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void createRoomAndJoin() {
        var user = createLocalUser("test@example.com", "测试用户");
        Room room = roomService.createRoom("undercover", "测试房间", false, null, "voice", Map.of("playerCount", 6), user);
        Assertions.assertNotNull(room.getId());
        Assertions.assertEquals(1, room.getSeats().size());

        var guest = createLocalUser("guest-a@example.com", "访客A");
        roomService.joinRoom(room.getId(), guest.getNickname(), guest, null);
        Assertions.assertEquals(2, roomService.getRoom(room.getId()).getSeats().size());
    }

    @Test
    void createRoomShouldClampToGameMinimumPlayers() {
        var user = createLocalUser("min-clamp@example.com", "人数校验用户");

        Room undercoverRoom = roomService.createRoom(
                "undercover",
                "卧底最小人数房间",
                false,
                null,
                "text",
                Map.of("playerCount", 2),
                user
        );
        Assertions.assertEquals(4, undercoverRoom.getMaxPlayers());

        Room werewolfRoom = roomService.createRoom(
                "werewolf",
                "狼人最小人数房间",
                false,
                null,
                "text",
                Map.of("playerCount", 2),
                user
        );
        Assertions.assertEquals(6, werewolfRoom.getMaxPlayers());
    }

    @Test
    void joinRoomShouldAllowExistingAuthenticatedPlayerWhenRoomIsFull() {
        var host = createLocalUser("full-room-host@example.com", "满房房主");
        Room room = roomService.createRoom(
                "undercover",
                "满房重连测试",
                false,
                null,
                "text",
                Map.of("playerCount", 4),
                host
        );

        roomService.joinRoom(room.getId(), "游客A", createLocalUser("full-a@example.com", "游客A"), null);
        roomService.joinRoom(room.getId(), "游客B", createLocalUser("full-b@example.com", "游客B"), null);
        roomService.joinRoom(room.getId(), "游客C", createLocalUser("full-c@example.com", "游客C"), null);
        Assertions.assertEquals(4, roomService.getRoom(room.getId()).getSeats().size());

        var rejoinResult = roomService.joinRoom(room.getId(), host.getNickname(), host, null);
        Assertions.assertEquals(host.getId(), rejoinResult.getSeat().getPlayerId());
        Assertions.assertEquals(4, roomService.getRoom(room.getId()).getSeats().size());
    }

    @Test
    void privateRoomShouldRequirePassword() {
        var host = createLocalUser("private-host@example.com", "私密房主");
        var guest = createLocalUser("private-guest@example.com", "私密访客");
        Room room = roomService.createRoom("undercover", "私密房", true, "safe-pass", "text", Map.of("playerCount", 4), host);

        Assertions.assertThrows(ApiException.class, () -> roomService.joinRoom(room.getId(), guest.getNickname(), guest, null));
        Assertions.assertThrows(ApiException.class, () -> roomService.joinRoom(room.getId(), guest.getNickname(), guest, "bad-pass"));

        var result = roomService.joinRoom(room.getId(), guest.getNickname(), guest, "safe-pass");
        Assertions.assertEquals(guest.getId(), result.getSeat().getPlayerId());
    }

    @Test
    void addAiShouldRequireHost() {
        var host = createLocalUser("ai-host@example.com", "AI房主");
        var guest = createLocalUser("ai-guest@example.com", "AI访客");
        Room room = roomService.createRoom("undercover", "AI权限房", false, null, "text", Map.of("playerCount", 4), host);
        roomService.joinRoom(room.getId(), guest.getNickname(), guest, null);

        Assertions.assertThrows(ApiException.class, () -> roomService.addAi(room.getId(), "ai1", null));
        Assertions.assertThrows(ApiException.class, () -> roomService.addAi(room.getId(), "ai1", guest));

        Room updated = roomService.addAi(room.getId(), "ai1", host);
        Assertions.assertEquals(3, updated.getSeats().size());
    }

    @Test
    void listByGameShouldDefaultToWaitingRoomsWithPageLimit() {
        var user = createLocalUser("paged-host@example.com", "分页房主");
        roomService.createRoom("undercover", "分页房间A", false, null, "text", Map.of("playerCount", 4), user);
        roomService.createRoom("undercover", "分页房间B", false, null, "text", Map.of("playerCount", 4), user);

        var page = roomService.listByGame("undercover", com.aisocialgame.model.RoomStatus.WAITING, 1, 1);

        Assertions.assertEquals(1, page.getContent().size());
        Assertions.assertTrue(page.getTotalElements() >= 2);
        Assertions.assertEquals(1, page.getSize());
    }

    private User createLocalUser(String email, String nickname) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setUsername(email.substring(0, email.indexOf("@")));
        user.setPassword("{test}");
        user.setNickname(nickname);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + nickname);
        user.setLevel(1);
        user.setCoins(0);
        return userRepository.save(user);
    }
}
