package com.aisocialgame.service;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.websocket.GamePushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class RoomLifecycleTest {
    @Autowired RoomService service;
    @Autowired RoomRepository rooms;
    @Autowired PlatformTransactionManager manager;
    @Autowired GamePushService push;
    @Autowired RoomAccessPolicy access;
    final LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);

    User user() { User user = new User(); user.setId(UUID.randomUUID().toString()); user.setNickname("房主"); return user; }
    Room room(boolean privateRoom) { return service.createRoom("undercover", "生命周期测试", privateRoom, privateRoom ? "secret" : null, "text", Map.of("playerCount",4), user()); }
    RoomLifecycle at(LocalDateTime time) { return new RoomLifecycle(rooms, push, manager, Clock.fixed(time.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())); }
    void waiting(Room room, LocalDateTime since) { room.setWaitingSince(since); rooms.saveAndFlush(room); }

    @Test void exactBoundaryExpiresAndReadFailureKeepsCommittedState() {
        Room room = room(false); waiting(room, now.minusHours(3));
        assertEquals(RoomStatus.WAITING, at(now.minusNanos(1)).requireActive(room.getId()).getStatus());
        ApiException error = assertThrows(ApiException.class, () -> at(now).requireActive(room.getId()));
        assertEquals(410, error.getStatus().value()); assertEquals("ROOM_EXPIRED", error.getCode());
        Room saved = rooms.findById(room.getId()).orElseThrow();
        assertEquals(RoomStatus.EXPIRED, saved.getStatus()); assertEquals(now, saved.getExpiredAt());
        assertThrows(ApiException.class, () -> at(now.plusHours(1)).withActiveRoom(room.getId(), null, r -> fail("must not execute")));
        assertEquals(now, rooms.findById(room.getId()).orElseThrow().getExpiredAt());
    }

    @Test void expirationSurvivesOuterRollbackAndDoesNotRunOperation() {
        Room room = room(false); waiting(room, now.minusHours(3));
        assertThrows(ApiException.class, () -> new TransactionTemplate(manager).execute(tx ->
                at(now).withActiveRoom(room.getId(), null, r -> fail("expired operation"))));
        assertEquals(RoomStatus.EXPIRED, rooms.findById(room.getId()).orElseThrow().getStatus());
    }

    @Test void regularOperationsStillParticipateInOuterRollback() {
        Room room = room(false); waiting(room, now);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            at(now).withActiveRoom(room.getId(), null, r -> { r.setName("must roll back"); rooms.save(r); return null; });
            tx.setRollbackOnly();
        });
        assertEquals("生命周期测试", rooms.findById(room.getId()).orElseThrow().getName());
    }

    @Test void playingNeverExpiresAndNextWaitGetsANewOrigin() {
        Room room = room(false); waiting(room, LocalDateTime.now().minusHours(2));
        service.updateStatus(room.getId(), RoomStatus.PLAYING);
        assertNull(rooms.findById(room.getId()).orElseThrow().getWaitingSince());
        assertEquals(RoomStatus.PLAYING, at(now.plusYears(2)).requireActive(room.getId()).getStatus());
        service.updateStatus(room.getId(), RoomStatus.WAITING);
        LocalDateTime origin = rooms.findById(room.getId()).orElseThrow().getWaitingSince();
        assertTrue(origin.isAfter(LocalDateTime.now().minusMinutes(1)));
        service.updateStatus(room.getId(), RoomStatus.WAITING);
        assertEquals(origin, rooms.findById(room.getId()).orElseThrow().getWaitingSince());
    }

    @Test void joiningDoesNotExtendWaitAndExpiredMembersCannotRejoin() {
        Room room = room(true); LocalDateTime origin = LocalDateTime.now().minusHours(2).withNano(0); waiting(room, origin);
        User guest = user(); service.joinRoom(room.getId(), guest.getNickname(), guest, "secret");
        assertEquals(origin, rooms.findById(room.getId()).orElseThrow().getWaitingSince());
        Room latest = rooms.findById(room.getId()).orElseThrow(); waiting(latest, LocalDateTime.now().minusHours(4));
        assertEquals("ROOM_EXPIRED", assertThrows(ApiException.class, () -> service.joinRoom(room.getId(), guest.getNickname(), guest, "secret")).getCode());
        assertThrows(ApiException.class, () -> service.updateStatus(room.getId(), RoomStatus.PLAYING));
    }

    @Test void publicPaginationExcludesPrivateAndExpiredBeforeCounting() {
        Room visible = room(false), hidden = room(true), old = room(false);
        waiting(old, LocalDateTime.now().minusHours(4));
        var page = service.listByGame("undercover", RoomStatus.WAITING, 1, 100);
        assertTrue(page.getContent().stream().anyMatch(r -> r.getId().equals(visible.getId())));
        assertTrue(page.getContent().stream().noneMatch(r -> r.getId().equals(hidden.getId()) || r.getId().equals(old.getId())));
        assertEquals(page.getTotalElements(), page.getContent().size());
    }

    @Test void searchFindsBothVisibilitiesAndSummaryDoesNotExposeSeatsOrPassword() throws Exception {
        User viewer = user();
        for (boolean privacy : List.of(false,true)) {
            Room room = room(privacy);
            assertTrue(room.getRoomCode().matches("[1-9][0-9]{5}"));
            assertEquals(room.getId(), service.search(room.getRoomCode(), viewer).getId());
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(new com.aisocialgame.dto.RoomEntryResponse(room, viewer.getId()));
            assertFalse(json.has("seats")); assertFalse(json.has("password")); assertFalse(json.has("config")); assertFalse(json.get("joined").asBoolean());
            assertEquals(privacy, json.get("passwordRequired").asBoolean());
            if (privacy) assertEquals("ROOM_PASSWORD_INVALID", assertThrows(ApiException.class, () -> service.joinRoom(room.getId(), "guest", viewer, "wrong")).getCode());
        }
        assertEquals(400, assertThrows(ApiException.class, () -> service.search("123", viewer)).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.entry("werewolf", room(false).getId())).getStatus().value());
    }

    @Test void startAndExpiryAreSerializedAndSweepIsRepeatable() throws Exception {
        Room room = room(false); waiting(room, now.minusHours(3));
        var lifecycle = at(now);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.<Callable<String>>of(
                () -> attemptStart(lifecycle, room.getId()), () -> attemptStart(lifecycle, room.getId())));
            for (var result : results) assertEquals("ROOM_EXPIRED", result.get());
        } finally { executor.shutdownNow(); }
        assertEquals(RoomStatus.EXPIRED, rooms.findById(room.getId()).orElseThrow().getStatus());
        lifecycle.sweep(); lifecycle.sweep();
    }
    String attemptStart(RoomLifecycle lifecycle, String id) {
        try { return lifecycle.withActiveRoom(id, null, r -> { r.setStatus(RoomStatus.PLAYING); rooms.save(r); return "started"; }); }
        catch (ApiException ex) { return ex.getCode(); }
    }

    @Test void aStartCommittedBeforeTheDeadlineCannotBeExpiredByAWaitingContender() throws Exception {
        Room room = room(false); waiting(room, now.minusHours(3));
        var instant = new java.util.concurrent.atomic.AtomicReference<>(now.minusSeconds(1).atZone(ZoneId.systemDefault()).toInstant());
        Clock mutable = new Clock() {
            public ZoneId getZone() { return ZoneId.systemDefault(); }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            public Instant instant() { return instant.get(); }
        };
        RoomLifecycle lifecycle = new RoomLifecycle(rooms, push, manager, mutable);
        var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = executor.submit(() -> lifecycle.withActiveRoom(room.getId(), null, r -> {
                acquired.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                r.setStatus(RoomStatus.PLAYING); r.setWaitingSince(null); rooms.save(r); return "started";
            }));
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            instant.set(now.atZone(ZoneId.systemDefault()).toInstant());
            var expire = executor.submit(() -> lifecycle.requireActive(room.getId()));
            release.countDown();
            assertEquals("started", start.get(3, TimeUnit.SECONDS));
            assertEquals(RoomStatus.PLAYING, expire.get(3, TimeUnit.SECONDS).getStatus());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void expiredMembersLoseChatAndSubscriptionPermissionAsWellAsLobbyRead() {
        Room room = room(false); waiting(room, LocalDateTime.now().minusHours(4));
        assertEquals("ROOM_EXPIRED", assertThrows(ApiException.class, () -> access.requireParticipant(room.getId(), room.getHostUserId())).getCode());
        assertEquals("ROOM_EXPIRED", assertThrows(ApiException.class, () -> access.requireLobbyRead(room.getId(), room.getHostUserId())).getCode());
        assertEquals("ROOM_EXPIRED", assertThrows(ApiException.class, () -> access.requireStateSubscription(room.getId(), room.getHostUserId())).getCode());
    }
}
