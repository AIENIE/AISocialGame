import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Bounded, metadata-only SELECT collector. No SQL input, application context or RPC clients. */
class ClosurePreflightData {
    static final String SERVER = "SELECT VERSION() AS version,@@character_set_database AS charset,@@collation_database AS collation,@@transaction_isolation AS isolationLevel,@@global.time_zone AS globalTimeZone,@@session.time_zone AS sessionTimeZone,@@system_time_zone AS systemTimeZone,DATABASE() AS databaseName";
    static final String COLUMNS = "SELECT table_name,column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position LIMIT 10001";
    static final String INDEXES = "SELECT table_name,index_name,column_name,seq_in_index,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index LIMIT 10001";
    static final String BUDGET = "SELECT id,consumed FROM ai_call_budgets WHERE id=? LIMIT 2";
    static final Set<String> ALLOWED = Set.of(SERVER,COLUMNS,INDEXES,BUDGET);
    static List<Map<String,Object>> query(Connection c,String sql,String parameter) throws SQLException {
        if(!ALLOWED.contains(sql))throw new IllegalArgumentException("SQL_NOT_ALLOWED");
        try(var s=c.prepareStatement(sql)) {
            s.setQueryTimeout(15);
            if(parameter!=null)s.setString(1,parameter);
            try(var rows=s.executeQuery()) {
                List<Map<String,Object>> result=new ArrayList<>();var meta=rows.getMetaData();
                while(rows.next()) {
                    if(result.size()>=10000)throw new IllegalArgumentException("METADATA_ROW_LIMIT");
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rows.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }
    public static void main(String[] args) {
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            if(args.length!=3 || !args[1].matches("jdbc:mysql://localbase\\.testhut\\.top:[0-9]{1,5}/aisocialgame\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000") || !args[2].equals("game-realism-v2-live-20260912"))throw new IllegalArgumentException("INVALID_SCOPE");
            Map<String,String> env=new HashMap<>();
            for(String line:Files.readAllLines(Path.of(args[0]))) {
                if(line.isBlank()||line.stripLeading().startsWith("#"))continue;
                int split=line.indexOf('=');if(split<1)throw new IllegalArgumentException("INVALID_ENVIRONMENT");
                String key=line.substring(0,split).trim().replaceFirst("^export\\s+", ""),v=line.substring(split+1);
                if(v.length()>=2 && ((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))v=v.substring(1,v.length()-1);
                if(env.putIfAbsent(key,v)!=null)throw new IllegalArgumentException("DUPLICATE_ENVIRONMENT");
            }
            try(var c=DriverManager.getConnection(args[1],env.get("SPRING_DATASOURCE_USERNAME"),env.get("SPRING_DATASOURCE_PASSWORD"))) {
                result.put("server",query(c,SERVER,null));
                var columns=query(c,COLUMNS,null);result.put("columns",columns);result.put("indexes",query(c,INDEXES,null));
                boolean budgetPresent=columns.stream().anyMatch(x->"ai_call_budgets".equals(x.get("TABLE_NAME"))||"ai_call_budgets".equals(x.get("table_name")));
                result.put("budgetTablePresent",budgetPresent);
                result.put("budget",budgetPresent?query(c,BUDGET,args[2]):List.of());
                result.put("status","PASS");
            }
        }catch(SQLException e){result= new LinkedHashMap<>(Map.of("status","UNKNOWN","reason","DATABASE_READ_FAILED","sqlState",Objects.toString(e.getSQLState(),"UNKNOWN"),"vendorCode",e.getErrorCode()));}
        catch(Exception e){result=new LinkedHashMap<>(Map.of("status","UNKNOWN","reason","COLLECTOR_INPUT_OR_PERMISSION_ERROR"));}
        try{System.out.println(new ObjectMapper().writeValueAsString(result));}catch(Exception e){System.out.println("{\"status\":\"UNKNOWN\",\"reason\":\"COLLECTOR_ERROR\"}");}
    }
}
