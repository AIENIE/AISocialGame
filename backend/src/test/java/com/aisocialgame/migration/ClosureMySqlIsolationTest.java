package com.aisocialgame.migration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClosureMySqlIsolationTest {
    @Test void businessDatabaseNamesAndInjectedTargetsAreRejectedBeforeConnection(){
        for(String name:new String[]{"aisocialgame","mysql","127.0.0.1/aisocialgame","fresh;DROP DATABASE mysql","localbase.testhut.top"})
            assertThrows(IllegalArgumentException.class,()->ClosureMySqlSupport.database(name));
    }
}
