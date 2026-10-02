package com.aisocialgame;

import com.aisocialgame.model.credit.AiCreditReservation;
import com.aisocialgame.repository.credit.AiCreditReservationRepository;
import com.aisocialgame.service.ai.v2.AiCallBudgetService;
import com.aisocialgame.service.credit.*;
import fireflychat.ai.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedAiClientTest {
    Server server; ManagedChannel channel;
    AiGatewayServiceGrpc.AiGatewayServiceBlockingStub stub;
    final AtomicInteger sends=new AtomicInteger(),prepares=new AtomicInteger();
    final PrepareCallBudgetResponse budget=PrepareCallBudgetResponse.newBuilder().setBudgetId("budget").setBudgetCredential("credential").build();
    final ChatCompletionsResponse response=ChatCompletionsResponse.newBuilder().setContent("committed").setPromptTokens(10).setCompletionTokens(5).build();
    final GetBudgetCallStatusResponse status=GetBudgetCallStatusResponse.newBuilder().setBudgetId("budget").setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setChat(response).build();
    @BeforeEach void start() throws Exception {
        String name=InProcessServerBuilder.generateName();
        server=InProcessServerBuilder.forName(name).directExecutor().addService(new AiGatewayServiceGrpc.AiGatewayServiceImplBase(){
            @Override public void prepareCallBudget(PrepareCallBudgetRequest request,StreamObserver<PrepareCallBudgetResponse> observer){
                prepares.incrementAndGet();observer.onNext(budget);observer.onCompleted();
            }
            @Override public void chatCompletions(ChatCompletionsRequest request,StreamObserver<ChatCompletionsResponse> observer){
                sends.incrementAndGet();assertEquals("credential",request.getBudgetCredential());observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
            }
            @Override public void getBudgetCallStatus(GetBudgetCallStatusRequest request,StreamObserver<GetBudgetCallStatusResponse> observer){
                assertEquals("same-request",request.getRequestId());observer.onNext(status);observer.onCompleted();
            }
        }).build().start();
        channel=InProcessChannelBuilder.forName(name).directExecutor().build();
        stub=AiGatewayServiceGrpc.newBlockingStub(channel);
    }
    @AfterEach void stop(){channel.shutdownNow();server.shutdownNow();}
    @Test void lostResponseUsesCommittedStatusWithoutRepeatingInference() {
        var escrow=mock(AiCreditEscrowService.class);var reservations=mock(AiCreditReservationRepository.class);var count=mock(AiCallBudgetService.class);
        var client=new BoundedAiClient(stub,escrow,reservations,count,true,100);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation();row.id="reservation";row.budgetId="budget";row.requestId="same-request";row.userId=85;row.projectKey="aisocialgame";row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true),new AiCreditEscrowService.Claim(row,false));
        doAnswer(invocation->{row.state="SETTLED";row.resultBase64=Base64.getEncoder().encodeToString(response.toByteArray());return null;}).when(escrow).reconcile(row.id,status);
        when(reservations.findById(row.id)).thenAnswer(invocation->Optional.of(row));
        assertEquals(response,client.chat(request,45));assertEquals(response,client.chat(request,45));
        assertEquals(1,sends.get());verify(count,times(1)).consume();
    }
    @Test void disabledAndInsufficientCreditNeverReachInference() {
        var escrow=mock(AiCreditEscrowService.class);var reservations=mock(AiCreditReservationRepository.class);var count=mock(AiCallBudgetService.class);
        var request=ChatCompletionsRequest.getDefaultInstance();
        assertThrows(RuntimeException.class,()->new BoundedAiClient(stub,escrow,reservations,count,false,100).chat(request,45));
        assertEquals(0,prepares.get());verifyNoInteractions(escrow,count);
        when(escrow.reserve(request,budget)).thenThrow(new IllegalStateException("insufficient credit"));
        assertThrows(RuntimeException.class,()->new BoundedAiClient(stub,escrow,reservations,count,true,100).chat(request,45));
        assertEquals(0,sends.get());verifyNoInteractions(count);
    }
}
