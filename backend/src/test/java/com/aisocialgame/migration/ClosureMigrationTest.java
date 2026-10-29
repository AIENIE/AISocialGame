package com.aisocialgame.migration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class ClosureMigrationTest {
    @Test void usageTablePreservesUnknownMeasurementsAcrossRepeatedDdl() throws Exception {
        String sql=Files.readString(Path.of("sql/20260922_milestone_closure.sql"));
        try(var db=DriverManager.getConnection("jdbc:h2:mem:closure-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(var st=db.createStatement()) {
                st.execute(sql);
                st.execute("INSERT INTO ai_call_usage(id,started_epoch_ms,admitted,prompt_tokens) VALUES('attempt',123,TRUE,NULL)");
                st.execute(sql);
                try(var row=st.executeQuery("SELECT prompt_tokens,completion_tokens,completed_epoch_ms FROM ai_call_usage WHERE id='attempt'")){assertTrue(row.next());for(int i=1;i<=3;i++)assertNull(row.getObject(i));}
            }
        }
    }
}
