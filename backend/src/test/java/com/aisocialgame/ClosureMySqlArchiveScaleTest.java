package com.aisocialgame;

import com.aisocialgame.migration.ClosureMySqlSupport;
import com.aisocialgame.service.ReplayArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AIENIE_CLOSURE_MYSQL", matches = "1")
class ClosureMySqlArchiveScaleTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) { ClosureMySqlSupport.properties(registry); }
    @Autowired ReplayArchiveService archives;

    @Test void visiblePaginationRemainsBoundedAtOneThousandAndOneHundredThousandArchives() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection database = ClosureMySqlSupport.connect("runtime")) {
            database.setAutoCommit(false);
            String insertArchive = "INSERT INTO game_archives "
                    + "(id,room_id,game_id,room_name,player_count,total_rounds,duration_seconds,event_count,finished_at,public_replay,host_user_id) "
                    + "VALUES (?, 'audit-scale-room', 'audit_archive_scale', 'Archive scale', 1, 1, 0, 0, DATE_ADD('2026-01-01', INTERVAL ? SECOND), ?, NULL)";
            try (PreparedStatement archive = database.prepareStatement(insertArchive);
                 PreparedStatement participant = database.prepareStatement(
                         "INSERT INTO archive_participants (archive_id,player_id) VALUES (?, 'audit-scale-viewer')")) {
                for (int row = 0; row < 100_000; row++) {
                    String id = "audit-scale-" + String.format(java.util.Locale.ROOT, "%06d", row);
                    archive.setString(1, id);
                    archive.setInt(2, row);
                    archive.setBoolean(3, row % 2 == 0);
                    archive.addBatch();
                    if (row % 101 == 0) {
                        participant.setString(1, id);
                        participant.addBatch();
                    }
                    if ((row + 1) % 1_000 == 0) {
                        archive.executeBatch();
                        participant.executeBatch();
                        database.commit();
                        if (row + 1 == 1_000 || row + 1 == 100_000) {
                            int expected = (row + 1) / 2;
                            for (int candidate = 0; candidate <= row; candidate += 101) if (candidate % 2 != 0) expected++;
                            Instant start = Instant.now();
                            var page = archives.search("audit_archive_scale", null, null, null,
                                    0, 10_000, "audit-scale-viewer");
                            long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                            assertEquals(100, page.getItems().size());
                            assertEquals(100, page.getSize());
                            assertEquals(expected, page.getTotal());
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("archives", row + 1);
                            sample.put("returnedEntities", page.getItems().size());
                            sample.put("visibleTotal", page.getTotal());
                            sample.put("elapsedMs", elapsedMs);
                            sample.put("explain", explain(database));
                            results.add(sample);
                        }
                    }
                }
            }
        }
        assertEquals(2, results.size());
        assertTrue(((Number) results.get(1).get("visibleTotal")).longValue() > 50_000);
        ClosureMySqlSupport.evidence("archive-pagination-scale", results);
    }

    private List<Map<String, Object>> explain(Connection database) throws Exception {
        String sql = "EXPLAIN SELECT a.id FROM game_archives a WHERE a.game_id='audit_archive_scale' "
                + "AND (a.public_replay=1 OR a.host_user_id='audit-scale-viewer' "
                + "OR EXISTS (SELECT 1 FROM archive_participants p WHERE p.archive_id=a.id AND p.player_id='audit-scale-viewer')) "
                + "ORDER BY a.finished_at DESC,a.id DESC LIMIT 100";
        List<Map<String, Object>> plan = new ArrayList<>();
        try (var query = database.createStatement(); var rows = query.executeQuery(sql)) {
            while (rows.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (String field : List.of("select_type", "table", "type", "possible_keys", "key", "rows", "Extra"))
                    item.put(field, rows.getObject(field));
                plan.add(item);
            }
        }
        return plan;
    }
}
