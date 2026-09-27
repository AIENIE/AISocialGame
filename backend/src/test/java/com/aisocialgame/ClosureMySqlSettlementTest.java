package com.aisocialgame;

import com.aisocialgame.migration.ClosureMySqlSupport;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.StatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class ClosureMySqlSettlementTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { ClosureMySqlSupport.properties(registry); }
    @Autowired StatsService stats;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void concurrentFirstCreationAndRepeatedArchivesNeverLoseOrDuplicateRewards() throws Exception {
        String id=UUID.randomUUID().toString();
        users.saveAndFlush(new User(id,id+"@example.invalid","unused","name","",19,1));
        User staleProfile=users.findById(id).orElseThrow();
        var player=new GamePlayerState(id,"name",0,false,null,"");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++) {
                String archive="stats-"+id+"-"+(i%2);
                futures.add(pool.submit(()->{start.await();stats.recordResult(archive,"undercover",List.of(player,player),Set.of(id));return null;}));
            }
            start.countDown();for(var future:futures) future.get(30,TimeUnit.SECONDS);
        }
        staleProfile.setNickname("updated after reward");
        users.saveAndFlush(staleProfile);
        assertEquals("updated after reward",users.findById(id).orElseThrow().getNickname());
        assertEquals(79,users.findById(id).orElseThrow().getCoins());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM player_settlements WHERE player_id=?",Integer.class,id));
        for(String game:List.of("undercover","total")) {
            assertEquals(2,jdbc.queryForObject("SELECT games_played FROM player_stats WHERE id=?",Integer.class,id+":"+game));
            assertEquals(30,jdbc.queryForObject("SELECT score FROM player_stats WHERE id=?",Integer.class,id+":"+game));
        }
        String rollback="rollback-"+id;
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{stats.recordResult(rollback,"undercover",List.of(player),Set.of());throw new IllegalStateException("rollback fixture");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM player_settlements WHERE archive_id=?",Integer.class,rollback));
        assertEquals(79,users.findById(id).orElseThrow().getCoins());
        stats.recordResult(rollback,"undercover",List.of(player),Set.of());
        assertEquals(87,users.findById(id).orElseThrow().getCoins());
        ClosureMySqlSupport.evidence("atomic-settlement",Map.of("concurrentRequests",20,"archives",2,"duplicatePlayersIgnored",true,"rollbackAtomic",true));
    }
}
