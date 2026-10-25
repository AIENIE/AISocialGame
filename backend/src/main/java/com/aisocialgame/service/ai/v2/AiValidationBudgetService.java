package com.aisocialgame.service.ai.v2;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Room;
import com.aisocialgame.repository.AiCallUsageRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An acceptance-run stop line over authoritative charges; never reserves or transfers credits. */
@Service
public class AiValidationBudgetService {
    public static final String EXHAUSTED = "AI_VALIDATION_BUDGET_EXHAUSTED";
    public static final String RUN_ID_KEY = "validationRunId";
    public static final String BUDGET_KEY = "validationBudgetPoints";

    public record RunStatus(String runId, long budgetPoints, List<String> roomIds, long spentPoints,
                            long pendingCallCount, boolean exhausted) { }

    private final RoomRepository rooms;
    private final AiCallUsageRepository usages;
    private final AiCreditReservationRepository reservations;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public AiValidationBudgetService(RoomRepository rooms, AiCallUsageRepository usages, AiCreditReservationRepository reservations,
                                     PlatformTransactionManager manager) {
        this.rooms = rooms;
        this.usages = usages;
        this.reservations = reservations;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Serialize registry changes in the single local runtime; row locks preserve existing room edits. */
    public synchronized RunStatus registerRoom(String runId, String roomId, long budgetPoints) {
        requireRunId(runId);
        if (budgetPoints <= 0) throw invalid("验收预算须为正整数");
        return write.execute(tx -> {
            List<String> ids = new ArrayList<>(runRooms(runId).stream().map(Room::getId).toList());
            if (!ids.contains(roomId)) ids.add(roomId);
            ids.sort(String::compareTo);
            List<Room> members = ids.stream().map(id -> rooms.findByIdForUpdate(id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"))).toList();
            for (Room room : members) {
                if (!"NORMAL".equals(room.getSource())) throw invalid("仅普通房间可以加入验收预算");
                String current = runId(room);
                if (!current.isBlank() && !current.equals(runId))
                    throw new ApiException(HttpStatus.CONFLICT, "房间已属于其他验收，不能重新归属", "AI_VALIDATION_RUN_CONFLICT", Map.of());
            }
            var totals = totals(ids);
            if (budgetPoints < totals.spentPoints()) throw invalid("验收预算不能低于当前累计实扣");
            for (Room room : members) {
                if (runId.equals(runId(room)) && budgetPoints == budget(room)) continue;
                var configuration = new LinkedHashMap<>(room.getPrivateConfig());
                configuration.put(RUN_ID_KEY, runId);
                configuration.put(BUDGET_KEY, budgetPoints);
                room.setPrivateConfig(configuration);
            }
            rooms.saveAllAndFlush(members);
            return result(runId, budgetPoints, ids, totals);
        });
    }

    public RunStatus status(String runId) {
        requireRunId(runId);
        return read.execute(tx -> summarize(runId, runRooms(runId)));
    }

    public Optional<RunStatus> roomStatus(String roomId) {
        if (roomId == null || roomId.isBlank()) return Optional.empty();
        return read.execute(tx -> rooms.findById(roomId).flatMap(room -> {
            String id = runId(room);
            return id.isBlank() ? Optional.empty() : Optional.of(summarize(id, runRooms(id)));
        }));
    }

    public boolean exhausted(String roomId) {
        return roomStatus(roomId).map(RunStatus::exhausted).orElse(false);
    }

    /** Called only for a new transmission; recovering an existing request bypasses this check. */
    public void requireCallAllowed(String roomId) {
        roomStatus(roomId).filter(RunStatus::exhausted).ifPresent(status -> {
            throw new ApiException(HttpStatus.CONFLICT, "验收已达到实际积分预算，已停止新调用", EXHAUSTED,
                    Map.of("runId", status.runId(), "budgetPoints", status.budgetPoints(),
                            "spentPoints", status.spentPoints(), "pendingCallCount", status.pendingCallCount()));
        });
    }

    private List<Room> runRooms(String id) {
        return rooms.findValidationRooms().stream().filter(room -> id.equals(runId(room)))
                .sorted(Comparator.comparing(Room::getId)).toList();
    }

    private RunStatus summarize(String id, List<Room> members) {
        if (members.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "验收记录不存在");
        long limit = budget(members.getFirst());
        if (limit <= 0 || members.stream().anyMatch(room -> budget(room) != limit))
            throw new ApiException(HttpStatus.CONFLICT, "验收预算配置不一致，请调整后继续", "AI_VALIDATION_BUDGET_INVALID", Map.of());
        List<String> ids = members.stream().map(Room::getId).toList();
        return result(id, limit, ids, totals(ids));
    }

    private record Totals(long spentPoints, long pendingCallCount) { }

    private Totals totals(List<String> roomIds) {
        // Older local tables use different collations. Compare IDs to bound parameters, never across the two tables.
        var unique = new LinkedHashSet<>(usages.findAdmittedRequestIds(roomIds));
        unique.removeIf(id -> id == null || id.isBlank());
        var requestIds = new ArrayList<>(unique);
        long spent = 0, pending = 0;
        for (int from = 0; from < requestIds.size(); from += 500) {
            var batch = reservations.validationTotalsByRequestIds(requestIds.subList(from, Math.min(from + 500, requestIds.size())));
            spent += batch.getSpentPoints();
            pending += batch.getPendingCallCount();
        }
        return new Totals(spent, pending);
    }

    private RunStatus result(String id, long limit, List<String> ids, Totals totals) {
        return new RunStatus(id, limit, List.copyOf(ids), totals.spentPoints(), totals.pendingCallCount(), totals.spentPoints() >= limit);
    }

    private String runId(Room room) {
        Object value = room.getPrivateConfig().get(RUN_ID_KEY);
        return value instanceof String id ? id : "";
    }

    private long budget(Room room) {
        Object value = room.getPrivateConfig().get(BUDGET_KEY);
        return value instanceof Number amount ? amount.longValue() : 0;
    }

    private void requireRunId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}")) throw invalid("验收标识须为1-96位字母、数字、连字符或下划线");
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, "AI_VALIDATION_BUDGET_INVALID", Map.of());
    }
}
