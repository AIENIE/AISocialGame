package com.aisocialgame.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import static org.junit.jupiter.api.Assertions.*;

public final class ClosureMySqlSupport {
    private ClosureMySqlSupport() {}
    public static String run() {
        String id=System.getenv("AIENIE_CLOSURE_MYSQL_RUN");
        if (!"1".equals(System.getenv("AIENIE_CLOSURE_MYSQL")) || id==null || !id.matches("[a-f0-9]{32}")) throw new IllegalStateException("Explicit isolated MySQL runner required");
        return id;
    }
    public static String password() {return Objects.requireNonNull(System.getenv("AIENIE_CLOSURE_MYSQL_PASSWORD"));}
    public static String database(String suffix) {
        if(!Set.of("fresh","legacy","v2","runtime","admission","plan_fresh","plan_legacy","plan_recorded","trace_column_partial","trace_index_partial","public_column_partial","public_index_partial","room_lifecycle").contains(suffix)) throw new IllegalArgumentException("Test database only");
        return "closure_"+run()+"_"+suffix;
    }
    public static String url(String suffix) {
        int port=Integer.parseInt(System.getenv("AIENIE_CLOSURE_MYSQL_PORT"));
        if(port<1024 || port>65535)throw new IllegalArgumentException("Invalid isolated port");
        return "jdbc:mysql://127.0.0.1:"+port+"/"+(suffix==null?"":database(suffix))+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }
    public static Connection connect(String suffix) throws SQLException {
        run();
        Connection connection=DriverManager.getConnection(url(suffix),"root",password());
        try(var st=connection.createStatement();var rs=st.executeQuery("SELECT @@datadir,VERSION()")){
            String version = Objects.requireNonNull(System.getenv("AIENIE_CLOSURE_MYSQL_VERSION"));
            assertTrue(Set.of("8.4.11", "8.0.45").contains(version));
            assertTrue(rs.next()); assertTrue(rs.getString(1).replace('\\','/').contains("/runs/"+run()+"/data"));assertTrue(rs.getString(2).startsWith(version));
        } catch(Throwable e){connection.close();throw e;}
        return connection;
    }
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->url("runtime")); registry.add("spring.datasource.username",()->"root");registry.add("spring.datasource.password",ClosureMySqlSupport::password);
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");registry.add("spring.jpa.hibernate.ddl-auto",()->"none");registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
        registry.add("app.demo-seed-enabled",()->false);registry.add("app.game.scheduler-enabled",()->false);
    }
    public static void evidence(String name,Object value) throws Exception {
        Path dir=Path.of(System.getenv("AIENIE_CLOSURE_MYSQL_EVIDENCE"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name+".json").toFile(),value);
    }
}
