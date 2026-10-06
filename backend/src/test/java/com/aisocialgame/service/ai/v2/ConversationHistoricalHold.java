package com.aisocialgame.service.ai.v2;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, evidence-bound exception for one historical hold; never changes credit state. */
final class ConversationHistoricalHold {
    static final Set<String> FIELDS = Set.of("id", "budget_id", "request_id", "project_key", "user_id", "state", "reserved_temp", "reserved_permanent");

    static List<Map<String, Object>> verify(Object approved, List<Map<String, Object>> actual) {
        if (approved == null) {
            if (!actual.isEmpty()) throw new IllegalStateException("Unapproved credit hold");
            return List.of();
        }
        if (!(approved instanceof Map<?, ?> expected) || !expected.keySet().equals(FIELDS)
                || !"aisocialgame".equals(expected.get("project_key")) || number(expected.get("user_id")) != 85
                || !"HELD".equals(expected.get("state")) || number(expected.get("reserved_temp")) < 0
                || number(expected.get("reserved_permanent")) <= 0 || actual.size() != 1)
            throw new IllegalStateException("Invalid historical hold exception");
        Map<String, Object> row = actual.getFirst();
        for (String field : FIELDS) {
            Object value = expected.get(field);
            boolean matches = Set.of("user_id", "reserved_temp", "reserved_permanent").contains(field)
                    ? number(value) == number(row.get(field))
                    : value instanceof String text && !text.isBlank() && value.equals(row.get(field));
            if (!matches) throw new IllegalStateException("Historical hold mismatch: " + field);
        }
        return List.of(Map.copyOf(row));
    }

    private static long number(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw new IllegalStateException("Historical hold requires integral amounts and identity");
        return ((Number) value).longValue();
    }
}
