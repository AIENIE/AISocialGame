package com.aisocialgame;

import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.service.AiStreamConcurrencyLimiter;
import com.aisocialgame.service.CancellableAiTask;
import fireflychat.ai.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class AiCancellationTest {
    @Test void permitIsReleasedOnlyOnceEvenAfterAnotherRequestHasAcquired() {
        var limiter=new AiStreamConcurrencyLimiter(1);
        var first=limiter.tryAcquire("user");assertNotNull(first);assertNull(limiter.tryAcquire("user"));
        first.close();var second=limiter.tryAcquire("user");assertNotNull(second);
        first.close();first.close();assertNull(limiter.tryAcquire("user"));second.close();
        assertNotNull(limiter.tryAcquire("user"));
    }

    @Test void cancellingBlockedRpcClosesTransportAndReleasesWorkerAndPermit() throws Exception {
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        String name=InProcessServerBuilder.generateName();
        var server=InProcessServerBuilder.forName(name).directExecutor().addService(new AiGatewayServiceGrpc.AiGatewayServiceImplBase(){
            @Override public void chatCompletions(ChatCompletionsRequest request, StreamObserver<ChatCompletionsResponse> observer) {
                Context.current().addListener(ignored->cancelled.countDown(),Runnable::run);
                long remaining=Context.current().getDeadline().timeRemaining(TimeUnit.SECONDS);
                assertTrue(remaining>40 && remaining<=45);
                entered.countDown(); // Intentionally never responds.
            }
        }).build().start();
        var channel=InProcessChannelBuilder.forName(name).directExecutor().build();
        var client=new AiGrpcClient();ReflectionTestUtils.setField(client,"aiStub",AiGatewayServiceGrpc.newBlockingStub(channel));
        ReflectionTestUtils.setField(client,"isolatedTransportFixture",true);
        var limiter=new AiStreamConcurrencyLimiter(1);var workerExited=new CountDownLatch(1);
        var task=new CancellableAiTask(()->{
            try { client.chatCompletions("aisocialgame",1,"session","model",List.of()); }
            finally { workerExited.countDown(); }
        },limiter.tryAcquire("user"));
        try(var executor=Executors.newSingleThreadExecutor()) {
            executor.execute(task);assertTrue(entered.await(3,TimeUnit.SECONDS));
            task.cancel(true);task.cancel(true);
            assertTrue(cancelled.await(3,TimeUnit.SECONDS));assertTrue(workerExited.await(3,TimeUnit.SECONDS));
            var next=limiter.tryAcquire("user");assertNotNull(next);
            task.cancel(true);assertNull(limiter.tryAcquire("user"));next.close();
        } finally { channel.shutdownNow();server.shutdownNow(); }
    }

    @Test void cancelBeforeDispatchDoesNotExecuteWork() {
        var limiter=new AiStreamConcurrencyLimiter(1);
        var work=new java.util.concurrent.atomic.AtomicInteger();
        var task=new CancellableAiTask(work::incrementAndGet,limiter.tryAcquire("user"));
        task.cancel(true);task.run();assertEquals(0,work.get());
        assertNotNull(limiter.tryAcquire("user"));
    }
}
