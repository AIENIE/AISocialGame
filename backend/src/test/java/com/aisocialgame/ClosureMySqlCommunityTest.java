package com.aisocialgame;

import com.aisocialgame.migration.ClosureMySqlSupport;
import com.aisocialgame.model.CommunityPost;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.repository.CommunityLikeRepository;
import com.aisocialgame.service.CommunityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL", matches="1")
class ClosureMySqlCommunityTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { ClosureMySqlSupport.properties(registry); }
    @Autowired CommunityService community;
    @Autowired CommunityPostRepository posts;
    @Autowired CommunityLikeRepository likes;
    @Autowired com.aisocialgame.service.RoomService roomService;
    @Autowired com.aisocialgame.repository.RoomRepository rooms;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.aisocialgame.service.RoomLifecycle lifecycle;

    @Test void concurrentFinalSeatHasExactlyOneWinner() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        var room=new com.aisocialgame.model.Room(id,"undercover","last-seat",com.aisocialgame.model.RoomStatus.WAITING,1,false,
                null,"text",java.util.Map.of());
        rooms.saveAndFlush(room);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<Future<String>>();
            for(int i=0;i<2;i++) {
                int index=i;
                futures.add(executor.submit(()->{
                    var user=new User();user.setId(java.util.UUID.randomUUID().toString());
                    start.await();
                    try {roomService.joinRoom(id,"player-"+index,user,null);return "JOINED";}
                    catch(com.aisocialgame.exception.ApiException error){return error.getCode();}
                }));
            }
            start.countDown();
            var results=new java.util.ArrayList<String>();
            for(var future:futures) results.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("JOINED"::equals).count());
            assertEquals(1,results.stream().filter("ROOM_FULL"::equals).count());
        }
        var saved=rooms.findById(id).orElseThrow();
        assertEquals(1,saved.getSeats().size());
        assertEquals(1,saved.getSeatCount());
        ClosureMySqlSupport.evidence("concurrent-last-seat",java.util.Map.of("room",id,"seatCount",saved.getSeatCount()));
    }

    @Test void changedPasswordAfterPreflightCannotCommitJoin() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        rooms.saveAndFlush(new com.aisocialgame.model.Room(id,"undercover","password-race",com.aisocialgame.model.RoomStatus.WAITING,4,true,
                encoder.encode("previous"),"text",java.util.Map.of()));
        var verifiedSnapshot=rooms.findJoinSnapshot(id).orElseThrow();
        assertTrue(encoder.matches("previous",verifiedSnapshot.getPassword()));
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var changed=rooms.findByIdForUpdate(id).orElseThrow();
            changed.setPassword(encoder.encode("replacement"));
            rooms.saveAndFlush(changed);
        });
        var user=new User();user.setId(java.util.UUID.randomUUID().toString());
        var rejected=assertThrows(com.aisocialgame.exception.ApiException.class,()->
                lifecycle.withActiveRoom(id,null,room->ReflectionTestUtils.invokeMethod(roomService,
                        "joinVerified",room,"player",user,verifiedSnapshot)));
        assertEquals("ROOM_PASSWORD_CHANGED",rejected.getCode());
        assertTrue(rooms.findById(id).orElseThrow().getSeats().isEmpty());
        ClosureMySqlSupport.evidence("changed-password-after-preflight",java.util.Map.of("room",id,"rejectedCode",rejected.getCode()));
    }

    @Test void roomPasswordQuotaUsesDatabaseIdentityAcrossCaseVariants() {
        String id=java.util.UUID.randomUUID().toString();
        var room=new com.aisocialgame.model.Room(id,"undercover","private",com.aisocialgame.model.RoomStatus.WAITING,4,true,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("correct"),"text",java.util.Map.of());
        rooms.saveAndFlush(room);
        var user=new User();user.setId(java.util.UUID.randomUUID().toString());
        for(int i=0;i<5;i++) {
            var error=assertThrows(com.aisocialgame.exception.ApiException.class,()->roomService.joinRoom(id,"name",user,"incorrect"));
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,error.getStatus());
        }
        var rejected=assertThrows(com.aisocialgame.exception.ApiException.class,()->roomService.joinRoom(id.toUpperCase(java.util.Locale.ROOT),"name",user,"correct"));
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,rejected.getStatus());
    }

    @Test void concurrentVotesHaveOneSubjectAndPreserveHistoricalAggregate() throws Exception {
        var post = new CommunityPost(); post.setAuthorName("legacy"); post.setContent("preserved"); post.setLikes(17);
        String id = posts.saveAndFlush(post).getId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<24;i++) {
                int voter=i%6;
                futures.add(executor.submit(() -> {
                    start.await(); var user=new User();user.setId("voter-"+voter);
                    community.like(id,user); return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30,TimeUnit.SECONDS);
        }
        assertEquals(23,posts.findById(id).orElseThrow().getLikes());
        for(int i=0;i<6;i++) assertTrue(likes.existsById(new com.aisocialgame.model.CommunityLike.Key(id,"voter-"+i)));
        ClosureMySqlSupport.evidence("concurrent-community-votes",java.util.Map.of("requests",24,"voters",6,"historicalLikes",17,"finalLikes",23));
    }
}
