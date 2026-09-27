package com.aisocialgame.migration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class ClosureMigrationTest {
    @Test void additiveTableCanBeCreatedTwiceWithoutLosingLegacyOrUsageRows() throws Exception {
        String sql=Files.readString(Path.of("sql/20260922_milestone_closure.sql"));
        try(var db=DriverManager.getConnection("jdbc:h2:mem:closure-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(var st=db.createStatement()) {
                st.execute("CREATE TABLE legacy_archive(id VARCHAR(128) PRIMARY KEY, snapshot LONGTEXT)");
                st.execute("INSERT INTO legacy_archive VALUES('old','original')");
                st.execute(sql);
                st.execute("INSERT INTO ai_call_usage(id,started_epoch_ms,admitted,prompt_tokens) VALUES('attempt',123,TRUE,NULL)");
                st.execute(sql);
                try(var row=st.executeQuery("SELECT snapshot FROM legacy_archive WHERE id='old'")){assertTrue(row.next());assertEquals("original",row.getString(1));}
                try(var row=st.executeQuery("SELECT prompt_tokens,completion_tokens,completed_epoch_ms FROM ai_call_usage WHERE id='attempt'")){assertTrue(row.next());for(int i=1;i<=3;i++)assertNull(row.getObject(i));}
            }
        }
        String schema=Files.readString(Path.of("sql/schema.sql"));assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS ai_call_usage"));
    }
}
