package com.aisocialgame.service.ai.v2;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoricalHoldTest {
    private Map<String, Object> hold() {
        return Map.of("id", "reservation", "budget_id", "budget", "request_id", "request",
                "project_key", "aisocialgame", "user_id", 85L, "state", "HELD",
                "reserved_temp", 0L, "reserved_permanent", 1049600L);
    }
    @Test void onlyTheExactApprovedHoldPassesWithoutChangingIt() {
        var row = hold();
        assertEquals(List.of(row), ConversationHistoricalHold.verify(row, List.of(row)));
        assertEquals(List.of(), ConversationHistoricalHold.verify(null, List.of()));
    }
    @Test void everyIdentityAndAmountMismatchStopsCollection() {
        for (String field : ConversationHistoricalHold.FIELDS) {
            var changed = new HashMap<>(hold());
            changed.put(field, changed.get(field) instanceof Number ? 99L : "changed");
            assertThrows(IllegalStateException.class, () -> ConversationHistoricalHold.verify(hold(), List.of(changed)), field);
        }
    }
    @Test void newMissingOrUnapprovedHoldsStopCollection() {
        assertThrows(IllegalStateException.class, () -> ConversationHistoricalHold.verify(hold(), List.of(hold(), hold())));
        assertThrows(IllegalStateException.class, () -> ConversationHistoricalHold.verify(hold(), List.of()));
        assertThrows(IllegalStateException.class, () -> ConversationHistoricalHold.verify(null, List.of(hold())));
        var fractional = new HashMap<>(hold()); fractional.put("reserved_permanent", 1049600.5);
        assertThrows(IllegalStateException.class, () -> ConversationHistoricalHold.verify(fractional, List.of(hold())));
    }
}
