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
    long generationDelayMs, statusDelayMs;
    final java.util.concurrent.atomic.AtomicLong statusWindowMs=new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicReference<PrepareCallBudgetRequest> prepared = new java.util.concurrent.atomic.AtomicReference<>();
    PrepareCallBudgetResponse budget=PrepareCallBudgetResponse.newBuilder().setBudgetId("budget").setBudgetCredential("credential")
            .setPriceQuote(BudgetPriceQuote.newBuilder().setCurrency("CNY").setMaxCreditPoints(226)).build();
    final ChatCompletionsResponse response=ChatCompletionsResponse.newBuilder().setContent("committed").setPromptTokens(10).setCompletionTokens(5).build();
    final GetBudgetCallStatusResponse status=GetBudgetCallStatusResponse.newBuilder().setBudgetId("budget").setState(BudgetCallState.BUDGET_CALL_STATE_SUCCEEDED).setChat(response).build();
    @BeforeEach void start() throws Exception {
        String name=InProcessServerBuilder.generateName();
        server=InProcessServerBuilder.forName(name).directExecutor().addService(new AiGatewayServiceGrpc.AiGatewayServiceImplBase(){
            @Override public void prepareCallBudget(PrepareCallBudgetRequest request,StreamObserver<PrepareCallBudgetResponse> observer){
                prepared.set(request);prepares.incrementAndGet();observer.onNext(budget);observer.onCompleted();
            }
            @Override public void chatCompletions(ChatCompletionsRequest request,StreamObserver<ChatCompletionsResponse> observer){
                sends.incrementAndGet();assertEquals("credential",request.getBudgetCredential());pause(generationDelayMs);observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
            }
            @Override public void getBudgetCallStatus(GetBudgetCallStatusRequest request,StreamObserver<GetBudgetCallStatusResponse> observer){
                statusWindowMs.set(Context.current().getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS));
                pause(statusDelayMs);
                assertEquals("same-request",request.getRequestId());observer.onNext(status);observer.onCompleted();
            }
        }).build().start();
        channel=InProcessChannelBuilder.forName(name).directExecutor().build();
        stub=AiGatewayServiceGrpc.newBlockingStub(channel);
    }
    @AfterEach void stop(){channel.shutdownNow();server.shutdownNow();}
    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    @Test void lostResponseUsesCommittedStatusWithoutRepeatingInference() {
        var escrow=mock(AiCreditEscrowService.class);var reservations=mock(AiCreditReservationRepository.class);var count=mock(AiCallBudgetService.class);
        var client=new BoundedAiClient(stub,escrow,reservations,count,true,8192);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation();row.id="reservation";row.budgetId="budget";row.requestId="same-request";row.userId=85;row.projectKey="aisocialgame";row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true),new AiCreditEscrowService.Claim(row,false));
        doAnswer(invocation->{row.state="SETTLED";row.resultBase64=Base64.getEncoder().encodeToString(response.toByteArray());return null;}).when(escrow).reconcile(row.id,status);
        when(reservations.findById(row.id)).thenAnswer(invocation->Optional.of(row));
        assertEquals(response,client.chat(request,45));assertEquals(response,client.chat(request,45));
        assertEquals(8192,prepared.get().getMaxOutputTokens());
        assertEquals("CNY",prepared.get().getBillingCurrency());
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
    }    @Test void uncertainResultStaysHeldAndDuplicateCallOnlyQueriesStatus() {
        var escrow=mock(AiCreditEscrowService.class); var reservations=mock(AiCreditReservationRepository.class);
        var count=mock(AiCallBudgetService.class); var client=new BoundedAiClient(stub,escrow,reservations,count,true,100);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation(); row.id="held"; row.budgetId="budget"; row.requestId="same-request";
        row.userId=85; row.projectKey="aisocialgame"; row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true),new AiCreditEscrowService.Claim(row,false));
        when(reservations.findById(row.id)).thenReturn(Optional.of(row));
        // Even an unavailable/invalid status must not release the reservation or infer again.
        doThrow(new IllegalStateException("status unavailable")).when(escrow).reconcile(row.id,status);
        for(int i=0;i<2;i++) assertEquals("BUDGET_RECONCILIATION_REQUIRED",
                assertThrows(com.aisocialgame.exception.ApiException.class,()->client.chat(request,45)).getCode());
        assertEquals("HELD",row.state); assertEquals(1,sends.get()); verify(count,times(1)).consume();
    }

    @Test void expiredGenerationWindowStillQueriesAndSettlesCommittedResult() {
        generationDelayMs=1100; statusDelayMs=100;
        var escrow=mock(AiCreditEscrowService.class); var reservations=mock(AiCreditReservationRepository.class);
        var client=new BoundedAiClient(stub,escrow,reservations,mock(AiCallBudgetService.class),true,100);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation(); row.id="late-result"; row.budgetId="budget"; row.requestId="same-request";
        row.userId=85; row.projectKey="aisocialgame"; row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true));
        when(reservations.findById(row.id)).thenReturn(Optional.of(row));
        doAnswer(invocation->{row.state="SETTLED";row.resultBase64=Base64.getEncoder().encodeToString(response.toByteArray());return null;})
                .when(escrow).reconcile(row.id,status);
        assertEquals(response,client.chat(request,1));
        assertTrue(statusWindowMs.get()>3000,"Settlement must receive its own query timeout");
        assertEquals(1,sends.get()); assertEquals("SETTLED",row.state);
    }
    @Test void missingMonetaryQuoteNeverFallsBackToTokenReservation() {
        budget=budget.toBuilder().clearPriceQuote().build();
        var escrow=mock(AiCreditEscrowService.class);
        var client=new BoundedAiClient(stub,escrow,mock(AiCreditReservationRepository.class),mock(AiCallBudgetService.class),true,8192);
        assertEquals("BUDGET_PRICING_UNAVAILABLE",assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->client.chat(ChatCompletionsRequest.newBuilder().setRequestId("same-request").build(),45)).getCode());
        verify(escrow,never()).reserve(any(),any()); assertEquals(0,sends.get());
    }
    @Test void committedReceiptReplaysBeforePreparingAnyNewPrice() {
        var escrow=mock(AiCreditEscrowService.class);var reservations=mock(AiCreditReservationRepository.class);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation();row.state="SETTLED";row.requestHash=AiCreditEscrowService.hash(request.toByteArray());
        row.resultBase64=Base64.getEncoder().encodeToString(response.toByteArray());
        when(reservations.findByIdentityHash(anyString())).thenReturn(Optional.of(row));
        assertEquals(response,new BoundedAiClient(stub,escrow,reservations,mock(AiCallBudgetService.class),true,8192).chat(request,1));
        assertEquals(0,prepares.get());assertEquals(0,sends.get());
        assertEquals("BUDGET_REQUEST_CONFLICT",assertThrows(com.aisocialgame.exception.ApiException.class,
                ()->new BoundedAiClient(stub,escrow,reservations,mock(AiCallBudgetService.class),true,8192).chat(request.toBuilder().setModel("changed").build(),1)).getCode());
    }
    @Test void localAdmissionFailureKeepsItsCauseWithoutSendingOrClaimingReconciliation() {
        var escrow=mock(AiCreditEscrowService.class);var reservations=mock(AiCreditReservationRepository.class);var count=mock(AiCallBudgetService.class);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").build();
        var row=new AiCreditReservation();row.id="not-dispatched";row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true));
        var blocked=new com.aisocialgame.service.safety.AiCallBlockedException(com.aisocialgame.service.safety.AiCallBlockedException.Reason.CALL_BUDGET_EXHAUSTED);
        doThrow(blocked).when(count).consume();
        assertSame(blocked,assertThrows(com.aisocialgame.service.safety.AiCallBlockedException.class,
                ()->new BoundedAiClient(stub,escrow,reservations,count,true,8192).chat(request,45)));
        assertEquals(0,sends.get());assertEquals("HELD",row.state);verify(escrow,never()).reconcile(anyString(),any());
    }

    @Test void confirmedNotSentFailureIsTheOnlyRetryableExecutionError() {
        var escrow=mock(AiCreditEscrowService.class); var reservations=mock(AiCreditReservationRepository.class);
        var client=new BoundedAiClient(stub,escrow,reservations,mock(AiCallBudgetService.class),true,100);
        var request=ChatCompletionsRequest.newBuilder().setRequestId("same-request").setUserId(85).setProjectKey("aisocialgame").build();
        var row=new AiCreditReservation(); row.id="released"; row.budgetId="budget"; row.requestId="same-request";
        row.userId=85; row.projectKey="aisocialgame"; row.state="HELD";
        when(escrow.reserve(request,budget)).thenReturn(new AiCreditEscrowService.Claim(row,true));
        when(reservations.findById(row.id)).thenReturn(Optional.of(row));
        doAnswer(invocation->{row.state="RELEASED"; return null;}).when(escrow).reconcile(row.id,status);
        assertEquals("AI_REQUEST_NOT_SENT",assertThrows(com.aisocialgame.exception.ApiException.class,()->client.chat(request,45)).getCode());
        assertEquals(1,sends.get());
    }

}
