package com.aisocialgame.service;

import com.aisocialgame.dto.ws.PrivateEvent;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.websocket.GamePushService;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;

/** Serializes expiration with room mutations. A 410 is raised only after expiration commits. */
@Service
public class RoomLifecycle {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final RoomRepository rooms;
    private final GamePushService push;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final TransactionTemplate expirationTransaction;

    @org.springframework.beans.factory.annotation.Autowired
    public RoomLifecycle(RoomRepository rooms, @org.springframework.context.annotation.Lazy GamePushService push, PlatformTransactionManager manager) {
        this(rooms, push, manager, Clock.systemDefaultZone());
    }

    RoomLifecycle(RoomRepository rooms, GamePushService push, PlatformTransactionManager manager, Clock clock) {
        this.rooms = rooms;
        this.push = push;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.expirationTransaction = new TransactionTemplate(manager);
        this.expirationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static boolean expired(Room room, LocalDateTime now) {
        return room.getStatus() == RoomStatus.EXPIRED || room.getStatus() == RoomStatus.WAITING
                && (room.getWaitingSince() == null || !now.isBefore(room.getWaitingSince().plusHours(3)));
    }

    public static ApiException expiredError() {
        return new ApiException(HttpStatus.GONE, "房间已失效", "ROOM_EXPIRED", Map.of());
    }

    public Room requireActive(String roomId) {
        // Most reads need no write lock; the deadline itself is authoritative even between sweeps.
        Room room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        if (expired(room, LocalDateTime.now(clock))) {
            Room checked = expirationTransaction.execute(tx -> {
                Room locked = rooms.findByIdForUpdate(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
                if (expired(locked, LocalDateTime.now(clock))) expire(locked);
                return locked;
            });
            if (checked.getStatus() == RoomStatus.EXPIRED) throw expiredError();
            return checked;
        }
        return room;
    }

    public <T> T withActiveRoom(String roomId, String gameId, Function<Room, T> operation) {
        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        Outcome<T> outcome = transaction.execute(status -> {
            Room room = rooms.findByIdForUpdate(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
            if (entityManager != null) entityManager.refresh(room);
            if (gameId != null && !gameId.equals(room.getGameId())) throw new ApiException(HttpStatus.NOT_FOUND, "房间与玩法不匹配");
            if (expired(room, LocalDateTime.now(clock))) {
                expire(room);
                if (outerTransaction) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completion) {
                        if (completion == STATUS_ROLLED_BACK) expirationTransaction.executeWithoutResult(tx -> {
                            Room retry = rooms.findByIdForUpdate(roomId).orElse(null);
                            if (retry != null && expired(retry, LocalDateTime.now(clock))) expire(retry);
                        });
                    }
                });
                return new Outcome<>(null, true);
            }
            return new Outcome<>(operation.apply(room), false);
        });
        if (outcome.expired()) throw expiredError();
        return outcome.value();
    }

    private void expire(Room room) {
        if (room.getStatus() == RoomStatus.EXPIRED) return;
        room.setStatus(RoomStatus.EXPIRED);
        room.setExpiredAt(LocalDateTime.now(clock));
        rooms.save(room);
        var recipients = new LinkedHashSet<String>();
        if (room.getHostUserId() != null) recipients.add(room.getHostUserId());
        room.getSeats().stream().filter(s -> !s.isAi()).forEach(s -> recipients.add(s.getPlayerId()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (String id : recipients) {
                    try { push.pushPrivate(id, new PrivateEvent("ROOM_EXPIRED", Map.of("roomId", room.getId()))); }
                    catch (RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(RoomLifecycle.class).warn("Room expiry notification unavailable roomId={}", room.getId()); }
                }
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.room.expiry-scan-ms:60000}")
    public void sweep() {
        // Bounded batches, without holding a transaction across the entire scan.
        for (String id : rooms.findExpirationCandidates(LocalDateTime.now(clock).minusHours(3), org.springframework.data.domain.PageRequest.of(0, 200))) {
            try { withActiveRoom(id, null, room -> null); }
            catch (ApiException ex) { if (ex.getStatus() != HttpStatus.GONE && ex.getStatus() != HttpStatus.NOT_FOUND) throw ex; }
        }
    }

    private record Outcome<T>(T value, boolean expired) { }
}
