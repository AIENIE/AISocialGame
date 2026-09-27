import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/** Offline collector boundary checks; no driver, socket, database, or RPC. */
class ClosurePreflightDataTest {
    public static void main(String[] args)throws Exception {
        for(String sql:List.of("DELETE FROM ai_call_budgets","SELECT * FROM game_states", "SELECT 1; UPDATE rooms SET status='ENDED'")) {
            try {ClosurePreflightData.query(null,sql,null);throw new AssertionError("Unlisted SQL accepted");}
            catch(IllegalArgumentException expected) {if(!"SQL_NOT_ALLOWED".equals(expected.getMessage()))throw expected;}
        }
        List<String> calls=new ArrayList<>();
        var meta=(ResultSetMetaData)Proxy.newProxyInstance(ClosurePreflightDataTest.class.getClassLoader(),new Class[]{ResultSetMetaData.class},(p,m,a)->m.getName().equals("getColumnCount")?0:null);
        var rows=(ResultSet)Proxy.newProxyInstance(ClosurePreflightDataTest.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){case "next"->false;case "getMetaData"->meta;default->null;});
        var statement=(PreparedStatement)Proxy.newProxyInstance(ClosurePreflightDataTest.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{calls.add(m.getName());return m.getName().equals("executeQuery")?rows:null;});
        var connection=(Connection)Proxy.newProxyInstance(ClosurePreflightDataTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(!m.getName().equals("prepareStatement")||!ClosurePreflightData.ALLOWED.contains(a[0]))throw new AssertionError("Unexpected connection operation");return statement;});
        if(!ClosurePreflightData.query(connection,ClosurePreflightData.BUDGET,"existing-run").isEmpty())throw new AssertionError("Missing row became a value");
        if(!calls.containsAll(List.of("setString","setQueryTimeout","executeQuery")))throw new AssertionError("Unbounded or nonparameterized SELECT");
        System.out.println("PASS fixed SELECT allowlist, parameter binding, bounded query and missing row semantics.");
    }
}
