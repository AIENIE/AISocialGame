package com.aisocialgame.integration.grpc.client;

import com.aisocialgame.exception.ApiException;
import fireflychat.billing.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class BillingCurrentFieldsTest {
    Server server; ManagedChannel channel; BillingGrpcClient client;
    ConvertPublicToProjectRequest sent;
    boolean unavailable, omitBalance;
    @BeforeEach void start() throws Exception {
        String name=InProcessServerBuilder.generateName();
        server=InProcessServerBuilder.forName(name).directExecutor()
            .addService(new BillingBalanceServiceGrpc.BillingBalanceServiceImplBase() {
                @Override public void getPublicBalance(GetPublicBalanceRequest request,StreamObserver<GetPublicBalanceResponse> out) {
                    if(unavailable){out.onError(Status.UNAVAILABLE.asRuntimeException());return;}
                    out.onNext(GetPublicBalanceResponse.newBuilder().setPublicPermanentCredits(0).setPublicPermanentTokens(999).build());out.onCompleted();
                }
            }).addService(new BillingConversionServiceGrpc.BillingConversionServiceImplBase() {
                @Override public void convertPublicToProject(ConvertPublicToProjectRequest request,StreamObserver<ConvertPublicToProjectResponse> out) {
                    sent=request;var response=ConvertPublicToProjectResponse.newBuilder().setPublicPermanentCredits(10).setPublicPermanentTokens(999);
                    if(!omitBalance)response.setProjectBalance(ProjectBalance.newBuilder().setTempCredits(3).setPermanentCredits(0).setTempTokens(99).setPermanentTokens(99));
                    out.onNext(response.build());out.onCompleted();
                }
            }).build().start();
        channel=InProcessChannelBuilder.forName(name).directExecutor().build();client=new BillingGrpcClient();
        ReflectionTestUtils.setField(client,"balanceStub",BillingBalanceServiceGrpc.newBlockingStub(channel));
        ReflectionTestUtils.setField(client,"conversionStub",BillingConversionServiceGrpc.newBlockingStub(channel));
    }
    @AfterEach void stop() { channel.shutdownNow();server.shutdownNow(); }
    @Test void zeroCurrentCreditsDoNotReadDeprecatedTokenMirrorsAndRpcFailureRemainsFailure() {
        assertEquals(0,client.getPublicPermanentTokens(85));
        unavailable=true;assertThrows(ApiException.class,()->client.getPublicPermanentTokens(85));
    }
    @Test void conversionUsesCurrentCreditsAndMissingBalanceCannotBecomeZero() {
        var balance=client.convertPublicToProject("original","aisocialgame",85,50);
        assertEquals(50,sent.getCredits());assertEquals(0,sent.getTokens());
        assertEquals(10,balance.publicPermanentTokens());assertEquals(3,balance.projectTempTokens());assertEquals(0,balance.projectPermanentTokens());
        omitBalance=true;assertThrows(ApiException.class,()->client.convertPublicToProject("same-identity","aisocialgame",85,50));
    }
}
