package com.aisocialgame;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.User;
import com.aisocialgame.dto.ws.SeatEvent;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.exception.ApiException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class RoomServiceTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.aisocialgame.service.AiNameService aiNameService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private GamePushService push;

    @org.junit.jupiter.api.BeforeEach void mockNames() {
        org.mockito.Mockito.when(aiNameService.localName(org.mockito.ArgumentMatchers.any())).thenReturn("离线昵称");
    }


    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;
    @Autowired private RoomRepository rooms;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(strings = {"JOIN", "AI_ADDED"})
    void seatNotificationsArePublishedOnlyAfterTheSeatIsCommitted(String eventType) {
        User host = createLocalUser(UUID.randomUUID() + "@example.com", "通知房主");
        Room room = roomService.createRoom("werewolf", "座位提交后通知", false, null, "text", Map.of("playerCount", 6), host);
        var events = new ArrayList<SeatEvent>();
        var beforeCommitReached = new AtomicBoolean();
        doAnswer(invocation -> {
            SeatEvent event = invocation.getArgument(1);
            Room committed = readCommittedRoom(room.getId());
            Assertions.assertTrue(beforeCommitReached.get());
            Assertions.assertEquals(2, committed.getSeats().size(), "A notification-triggered read must see the committed roster");
            Assertions.assertEquals(event.seat().getPlayerId(), committed.getSeats().get(1).getPlayerId());
            events.add(event);
            return null;
        }).when(push).pushSeatChange(eq(room.getId()), any(SeatEvent.class));

        RoomSeat added = mutateSeatWithCommitObserver(eventType, room, host, () -> {
            verifyNoInteractions(push);
            beforeCommitReached.set(true);
        });

        Assertions.assertTrue(beforeCommitReached.get());
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(eventType, events.getFirst().type());
        Assertions.assertEquals(added.getPlayerId(), events.getFirst().seat().getPlayerId());
        Assertions.assertEquals(added.getDisplayName(), events.getFirst().seat().getDisplayName());
        Assertions.assertEquals(1, events.getFirst().seat().getSeatNumber());
        Assertions.assertEquals("AI_ADDED".equals(eventType), events.getFirst().seat().isAi());
        verify(push).pushSeatChange(eq(room.getId()), any(SeatEvent.class));
        verifyNoMoreInteractions(push);
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOIN", "AI_ADDED"})
    void rolledBackSeatsNeverPublishJoinOrAiAdded(String eventType) {
        User host = createLocalUser(UUID.randomUUID() + "@example.com", "回滚房主");
        Room room = roomService.createRoom("werewolf", "座位回滚不通知", false, null, "text", Map.of("playerCount", 6), host);
        var beforeCommitReached = new AtomicBoolean();

        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
                () -> mutateSeatWithCommitObserver(eventType, room, host, () -> {
                    beforeCommitReached.set(true);
                    verifyNoInteractions(push);
                    throw new IllegalStateException("commit rejected by test");
                }));

        Assertions.assertEquals("commit rejected by test", failure.getMessage());
        Assertions.assertTrue(beforeCommitReached.get());
        Assertions.assertEquals(1, readCommittedRoom(room.getId()).getSeats().size());
        verifyNoInteractions(push);
    }

    private RoomSeat mutateSeatWithCommitObserver(String eventType, Room room, User host, Runnable beforeCommit) {
        // Install the observer from a normal collaborator used inside the real
        // lifecycle transaction, including joinRoom's NOT_SUPPORTED boundary.
        Runnable observe = () -> {
            Assertions.assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { beforeCommit.run(); }
            });
        };
        if ("JOIN".equals(eventType)) {
            User guest = spy(createLocalUser(UUID.randomUUID() + "@example.com", "通知访客"));
            doAnswer(invocation -> { observe.run(); return "guest-avatar"; }).when(guest).getAvatar();
            return roomService.joinRoom(room.getId(), guest.getNickname(), guest, null).getSeat();
        }
        doAnswer(invocation -> { observe.run(); return "离线昵称"; }).when(aiNameService).localName(any());
        return roomService.addAi(room.getId(), "ai1", host).getSeats().getLast();
    }

    private Room readCommittedRoom(String roomId) {
        TransactionTemplate independentRead = new TransactionTemplate(transactionManager);
        independentRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independentRead.setReadOnly(true);
        return independentRead.execute(tx -> rooms.findById(roomId).orElseThrow());
    }

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
