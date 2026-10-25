package com.aisocialgame;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.AiCallUsage;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.AiCallUsageRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import com.aisocialgame.service.ai.v2.AiValidationBudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(classes = AiSocialGameApplication.class, properties = "app.ai.budget-reconcile-delay-ms=600000")
@ActiveProfiles("test")
class AiValidationBudgetIntegrationTest {
    @Autowired AiValidationBudgetService budgets;
    @Autowired RoomRepository rooms;
    @Autowired AiCallUsageRepository usages;
    @Autowired AiCreditReservationRepository reservations;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean AiGrpcClient client;

    @Test void requestBudgetMustBeAPositiveIntegralLong() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String value : new String[]{"0", "-1", "1.5", "9223372036854775808"}) {
                var request = new com.aisocialgame.controller.admin.AdminAiValidationController.BudgetRequest(new BigDecimal(value));
                assertFalse(validator.validate(request).isEmpty(), value);
            }
            var accepted = new com.aisocialgame.controller.admin.AdminAiValidationController.BudgetRequest(new BigDecimal("1000"));
            assertTrue(validator.validate(accepted).isEmpty());
        }
    }

    @Test void aggregatesOnlyThisRunsSettledCallsAcrossRoomsAndRestoresAfterRestart() {
        Room first = room(), second = room(), unrelated = room();
        String run = UUID.randomUUID().toString();
        budgets.registerRoom(run, first.getId(), 1000);
        budgets.registerRoom(run, second.getId(), 1000);
        call(first, "SETTLED", 410L, true);
        call(second, "SETTLED", 320L, true);
        call(first, "PENDING", null, true);
        call(second, "USAGE_UNKNOWN", null, true);
        call(first, "NOT_SENT", 999L, true);
        call(first, "SETTLED", 999L, false);
        call(unrelated, "SETTLED", 9999L, true);

        var restored = new AiValidationBudgetService(rooms, reservations, manager);
        var status = restored.status(run);
        assertEquals(730, status.spentPoints());
        assertEquals(2, status.pendingCallCount());
        assertEquals(2, status.roomIds().size());
        assertFalse(status.exhausted());
        assertDoesNotThrow(() -> restored.requireCallAllowed(first.getId()));
        assertTrue(restored.roomStatus(unrelated.getId()).isEmpty());
        assertDoesNotThrow(() -> restored.requireCallAllowed(null));
        verifyNoInteractions(client);
    }

    @Test void thresholdBlocksNewCallsButLateChargesRemainCountedExactlyOnce() {
        Room first = room(), second = room();
        String run = UUID.randomUUID().toString();
        budgets.registerRoom(run, first.getId(), 1000);
        budgets.registerRoom(run, second.getId(), 1000);
        call(first, "SETTLED", 1000L, true);
        AiCreditReservation late = call(second, "PENDING", null, true);

        assertTrue(budgets.exhausted(second.getId()));
        var blocked = assertThrows(ApiException.class, () -> budgets.requireCallAllowed(first.getId()));
        assertEquals(AiValidationBudgetService.EXHAUSTED, blocked.getCode());
        assertEquals(1000L, blocked.getDetails().get("spentPoints"));
        late.state = "SETTLED"; late.billedPoints = 37L;
        reservations.saveAndFlush(late);
        reservations.saveAndFlush(late);
        assertEquals(1037, budgets.status(run).spentPoints());
        assertEquals(0, budgets.status(run).pendingCallCount());
        assertThrows(ApiException.class, () -> budgets.registerRoom(run, second.getId(), 1036));

        budgets.registerRoom(run, first.getId(), 1100);
        assertEquals(1100, budgets.roomStatus(second.getId()).orElseThrow().budgetPoints());
        assertFalse(budgets.exhausted(first.getId()));
        assertEquals(37, reservations.findById(late.id).orElseThrow().billedPoints);
        verifyNoInteractions(client);
    }

    @Test void registrationIsIdempotentKeepsPrivateDataAndCannotReassignARoom() {
        Room room = room();
        String run = UUID.randomUUID().toString();
        var first = budgets.registerRoom(run, room.getId(), 1000);
        assertEquals(first, budgets.registerRoom(run, room.getId(), 1000));
        assertEquals("keep", rooms.findById(room.getId()).orElseThrow().getPrivateConfig().get("existing"));
        var conflict = assertThrows(ApiException.class, () -> budgets.registerRoom("different-run", room.getId(), 1000));
        assertEquals("AI_VALIDATION_RUN_CONFLICT", conflict.getCode());
        assertEquals(first, budgets.status(run));
        assertThrows(ApiException.class, () -> budgets.registerRoom(run, room.getId(), 0));
        assertThrows(ApiException.class, () -> budgets.registerRoom("invalid/run", room.getId(), 1000));
        Room lab = room(); lab.setSource("LAB"); rooms.saveAndFlush(lab);
        assertThrows(ApiException.class, () -> budgets.registerRoom("lab-run", lab.getId(), 1000));
    }

    @Test void concurrentRegistrationsShareOneLimitWithoutLosingMembers() throws Exception {
        Room first = room(), second = room();
        String run = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> budgets.registerRoom(run, first.getId(), 1000));
            var b = executor.submit(() -> budgets.registerRoom(run, second.getId(), 1000));
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, budgets.status(run).roomIds().size());
        assertEquals(1000, budgets.roomStatus(first.getId()).orElseThrow().budgetPoints());
        assertEquals(1000, budgets.roomStatus(second.getId()).orElseThrow().budgetPoints());
    }

    private Room room() {
        Room room = new Room(UUID.randomUUID().toString(), "werewolf", "Validation fixture", RoomStatus.WAITING,
                6, false, null, "text", Map.of("playerCount", 6));
        room.setPrivateConfig(new HashMap<>(Map.of("existing", "keep")));
        return rooms.saveAndFlush(room);
    }

    private AiCreditReservation call(Room room, String state, Long billed, boolean admitted) {
        String request = UUID.randomUUID().toString();
        AiCallUsage usage = new AiCallUsage(); usage.id = request; usage.roomId = room.getId();
        usage.admitted = admitted; usage.source = "AI_PLAYER"; usage.outcome = "RESPONSE";
        usages.saveAndFlush(usage);
        AiCreditReservation receipt = new AiCreditReservation();
        receipt.id = UUID.randomUUID().toString(); receipt.identityHash = request; receipt.requestHash = request;
        receipt.requestId = request; receipt.projectKey = "aisocialgame"; receipt.userId = 1;
        receipt.budgetId = UUID.randomUUID().toString(); receipt.state = state; receipt.billedPoints = billed;
        receipt.tempPortionsJson = "[]";
        return reservations.saveAndFlush(receipt);
    }
}
