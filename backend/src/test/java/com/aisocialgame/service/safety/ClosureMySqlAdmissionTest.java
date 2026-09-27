package com.aisocialgame.service.safety;

import com.aisocialgame.repository.*;
import com.aisocialgame.model.AiCallUsage;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.migration.ClosureMySqlSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL",matches="1")
class ClosureMySqlAdmissionTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class})
    @EntityScan(basePackages="com.aisocialgame.model")
    @EnableJpaRepositories(basePackages="com.aisocialgame.repository")
    @Import({AiSafetyService.class,SafetyAuditWriter.class})
    static class Config {}
    ConfigurableApplicationContext open() throws Exception {
        try(var ignored=ClosureMySqlSupport.connect("admission")){}
        // Command arguments override inherited environment/properties; this context has no application services or network clients.
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.datasource.url="+ClosureMySqlSupport.url("admission"),"--spring.datasource.username=root","--spring.datasource.password="+ClosureMySqlSupport.password(),
            "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.hibernate.ddl-auto=none","--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect","--logging.level.root=ERROR");
    }
    AiCallAdmission admission(ConfigurableApplicationContext app,String scope,AiCallAdmissionTest.MutableClock clock){
        return new AiCallAdmission(app.getBean(AiCallUsageRepository.class),app.getBean(PlatformTransactionManager.class),app.getBean(AiSafetyService.class),
                new MockEnvironment().withProperty("app.ai.safety.requests."+scope,"3"),clock);
    }
    AiSafetyContext context(){return AiSafetyContext.source("ISOLATED_MYSQL_TEST").user("test-user",null).room("test-room",null).model("mock");}
    @Test void concurrentAllScopesPersistAcrossContextRestartAndBlockedRpcNeverRuns()throws Exception{
        var clock=new AiCallAdmissionTest.MutableClock();
        for(String scope:List.of("user","room","model","project")){
            try(var app=open()){
                var rows=app.getBean(AiCallUsageRepository.class); rows.deleteAll();
                var service=admission(app,scope,clock);
                var pool=Executors.newFixedThreadPool(6);
                try{
                    List<Future<Boolean>> attempts=new ArrayList<>();
                    for(int i=0;i<10;i++){String id=scope+i;attempts.add(pool.submit(()->{try{service.begin(id,context());return true;}catch(AiCallBlockedException expected){return false;}}));}
                    int accepted=0;for(var f:attempts)if(f.get(30,TimeUnit.SECONDS))accepted++;
                    assertEquals(3,accepted,scope);assertEquals(3,rows.findAll().stream().filter(r->r.admitted).count());
                } finally{pool.shutdownNow();}
                service.finish(rows.findAll().stream().filter(r->r.admitted).findFirst().orElseThrow().id,"NO_RESPONSE",null,null);
                var finished=rows.findAll().stream().filter(r->r.completedEpochMs!=null).findFirst().orElseThrow();
                service.finish(finished.id,"RESPONSE",99L,99L);assertNull(rows.findById(finished.id).orElseThrow().promptTokens);
            }
            try(var app=open()){
                var service=admission(app,scope,clock);var events=app.getBean(AiSafetyEventRepository.class);long before=events.count();
                var channel=mock(io.grpc.Channel.class);
                var stub=fireflychat.ai.v1.AiGatewayServiceGrpc.newBlockingStub(channel);
                var client=new AiGrpcClient();ReflectionTestUtils.setField(client,"admission",service);ReflectionTestUtils.setField(client,"aiStub",stub);
                ReflectionTestUtils.setField(client,"isolatedTransportFixture",true);
                new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
                    assertThrows(AiCallBlockedException.class,()->service.begin("restart-"+scope,context())); tx.setRollbackOnly();
                });
                assertTrue(events.count()>before);verifyNoInteractions(channel);
                // AiCallScope supplies this user/model; user/project limits also cover the actual production client boundary.
                if(Set.of("project","model").contains(scope)){
                    assertThrows(AiCallBlockedException.class,()->client.chatCompletions("aisocialgame",1,"","mock",List.of(),"rpc-"+scope,0));verifyNoInteractions(channel);
                }
                clock.now+=60_001;service.begin("next-window-"+scope,context());
            }
        }
        ClosureMySqlSupport.evidence("admission-results",Map.of("scopes",List.of("user","room","model","project"),"contextRestart",true,"missingUsageUnknown",true,"rejectedAuditSurvivesRollback",true,"realRpcCalls",0));
    }
}
