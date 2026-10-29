package com.aisocialgame.service.ai.v2;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.repository.AiCallUsageRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiValidationBudgetServiceTest {
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final AiCallUsageRepository usages = mock(AiCallUsageRepository.class);
    private final AiCreditReservationRepository reservations = mock(AiCreditReservationRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AiValidationBudgetService service = new AiValidationBudgetService(rooms, usages, reservations, transactions);

    @BeforeEach void setup() {
        var room = new Room("room", "werewolf", "fixture", RoomStatus.WAITING, 6, false, null, "text", Map.of());
        room.setPrivateConfig(Map.of("validationRunId", "run", "validationBudgetPoints", 1000));
        when(rooms.findValidationRooms()).thenReturn(List.of(room));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test void emptyAndBlankUsageIdsReturnZeroWithoutAnEmptyReservationQuery() {
        when(usages.findAdmittedRequestIds(List.of("room")))
                .thenReturn(List.of()).thenReturn(Arrays.asList(null, "", " "));
        for (int i = 0; i < 2; i++) {
            var status = service.status("run");
            assertEquals(0, status.spentPoints());
            assertEquals(0, status.pendingCallCount());
            assertFalse(status.exhausted());
        }
        verifyNoInteractions(reservations);
        verify(transactions, times(2)).getTransaction(argThat(definition -> definition.isReadOnly()
                && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
    }

    @Test void batchesMoreThanOneThousandIdsWithoutChargingDuplicatesAcrossBatches() {
        var ids = new ArrayList<>(IntStream.rangeClosed(0, 1000).mapToObj(i -> "request-" + i).toList());
        ids.add("request-0"); ids.add("request-500"); ids.add("request-1000");
        ids.add(null); ids.add("");
        when(usages.findAdmittedRequestIds(List.of("room"))).thenReturn(ids);
        when(reservations.validationTotalsByRequestIds(anyCollection())).thenAnswer(call -> {
            Collection<String> batch = call.getArgument(0);
            assertFalse(batch.isEmpty());
            assertTrue(batch.size() <= 500, "Each database query must have a bounded parameter list.");
            long pending = batch.contains("request-1000") ? 1 : 0;
            long spent = batch.size() - pending;
            return new AiCreditReservationRepository.ValidationTotals() {
                public long getSpentPoints() { return spent; }
                public long getPendingCallCount() { return pending; }
            };
        });
        var status = service.status("run");
        assertEquals(1000, status.spentPoints());
        assertEquals(1, status.pendingCallCount());
        assertTrue(status.exhausted());
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<String>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(reservations, times(3)).validationTotalsByRequestIds(batches.capture());
        var queried = batches.getAllValues().stream().flatMap(Collection::stream).toList();
        assertEquals(1001, queried.size());
        assertEquals(1001, new HashSet<>(queried).size());
    }
}
