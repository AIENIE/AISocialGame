import java.nio.file.*;
import java.sql.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Local-only adapter for the exact release SQL; no application startup or demo seeding. */
public class RoomLifecycleMigration {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !java.util.Set.of("dry-run", "apply").contains(args[0])) throw new IllegalArgumentException("Mode required");
        if (!"local".equals(System.getenv("AISOCIAL_CLEANUP_ENV")) || !"windows-local".equals(System.getenv("AISOCIAL_CLEANUP_PLANE"))
                || !args[2].matches("jdbc:mysql://localmysql\\.testhut\\.top:[0-9]+/aisocialgame(?:\\?.*)?")) throw new IllegalArgumentException("Verified local target required");
        try (var db = DriverManager.getConnection(args[2], System.getenv("AISOCIAL_CLEANUP_DB_USERNAME"), System.getenv("AISOCIAL_CLEANUP_DB_PASSWORD"))) {
            if (!"aisocialgame".equals(db.getCatalog())) throw new IllegalArgumentException("Unexpected catalog");
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT status,COUNT(*) FROM rooms GROUP BY status")) {
                while (rows.next()) System.out.println("before " + rows.getString(1) + "=" + rows.getLong(2));
            }
            if (args[0].equals("dry-run")) { System.out.println("dry-run: no schema or data changed"); return; }
            try (var statement = db.prepareStatement("SET @room_migration_now=?")) {
                statement.setObject(1, java.time.LocalDateTime.now()); statement.execute();
            }
            ScriptUtils.executeSqlScript(db, new EncodedResource(new FileSystemResource(Path.of(args[1])), java.nio.charset.StandardCharsets.UTF_8));
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT status,COUNT(*) FROM rooms GROUP BY status")) {
                while (rows.next()) System.out.println("after " + rows.getString(1) + "=" + rows.getLong(2));
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM rooms WHERE room_code IS NULL OR room_code NOT REGEXP '^[1-9][0-9]{5}$'")) {
                rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("Invalid room number");
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM rooms WHERE status='EXPIRED' AND waiting_since IS NULL")) {
                rows.next(); System.out.println("expired with unknown waiting origin=" + rows.getLong(1));
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT id FROM rooms WHERE status='EXPIRED' AND waiting_since IS NULL ORDER BY id")) {
                while (rows.next()) System.out.println("unknown waiting origin room=" + rows.getString(1));
            }
            System.out.println("local room lifecycle migration complete");
        }
    }
}
