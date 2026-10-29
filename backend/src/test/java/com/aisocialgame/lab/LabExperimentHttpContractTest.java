package com.aisocialgame.lab;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.exception.GlobalExceptionHandler;
import com.aisocialgame.service.AdminAuthService;
import com.aisocialgame.web.CurrentAdminArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes=AiSocialGameApplication.class)
@ActiveProfiles("test")
class LabExperimentHttpContractTest {
    @Autowired ObjectMapper json;
    @Autowired RequestMappingHandlerAdapter http;
    LabService service;
    MockMvc mvc;

    @BeforeEach void controller() {
        service=mock(LabService.class);when(service.create(any(),eq("fixture-admin"))).thenReturn(Map.of("id","fixture-created"));
        mvc=MockMvcBuilders.standaloneSetup(new AdminLabController(service,mock(LabCoachService.class)))
                .setMessageConverters(http.getMessageConverters().toArray(org.springframework.http.converter.HttpMessageConverter[]::new))
                .setCustomArgumentResolvers(new CurrentAdminArgumentResolver()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void jacksonAndActualHttpConverterReadWerewolfBodyWithoutUndercoverOnlyFields() throws Exception {
        String body=werewolfBody();
        assertWerewolf(json.readValue(body,LabRequests.Experiment.class));
        assertWerewolf(read(body,LabRequests.Experiment.class));
    }

    @Test void explicitLegacyBooleanSeparatesDefaultHandlingFromOverloadedConstructorSelection() throws Exception {
        var body=json.readValue(werewolfBody(),Map.class);body.put("hasBlank",false);
        assertWerewolf(read(json.writeValueAsString(body),LabRequests.Experiment.class));
    }

    @Test void actualCreateHttpRouteDefaultsOmittedOrNullBlankOptionWithoutCallingLiveServices() throws Exception {
        String absent=werewolfBody();var nullable=json.readValue(absent,Map.class);nullable.put("hasBlank",null);
        send(absent).andExpect(status().isOk());send(json.writeValueAsString(nullable)).andExpect(status().isOk());
        var captured=ArgumentCaptor.forClass(LabRequests.Experiment.class);verify(service,times(2)).create(captured.capture(),eq("fixture-admin"));
        captured.getAllValues().forEach(this::assertWerewolf);
    }

    @Test void legacyUndercoverTrueAndTenArgumentConstructorRemainCompatible() throws Exception {
        String body="""
                {"name":"旧卧底请求","agentIds":["a","b","c","d"],"playerCount":4,"targetGames":2,
                 "wordPack":"daily","hasBlank":true,"budgetPoints":1000,
                 "coach":{"modelKey":"fixture","thinkingMode":"ENABLED","thinkingEffort":"LOW"},"initialExperienceIds":{}}
                """;
        var selection=new LabRequests.Selection("fixture",com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode.ENABLED,"LOW");
        var expected=new LabRequests.Experiment("旧卧底请求",List.of("a","b","c","d"),4,2,"daily",true,1000,selection,Map.of(),null);
        assertEquals(expected,json.readValue(body,LabRequests.Experiment.class));assertEquals(expected,read(body,LabRequests.Experiment.class));
        send(body).andExpect(status().isOk());verify(service).create(expected,"fixture-admin");
    }

    @Test void requiredNumbersStillRejectAbsentNullAndOutOfRangeThroughHttp() throws Exception {
        for(String field:List.of("playerCount","targetGames","budgetPoints")) {
            var body=json.readValue(werewolfBody(),Map.class);body.remove(field);send(json.writeValueAsString(body)).andExpect(status().isBadRequest());
            body.put(field,null);send(json.writeValueAsString(body)).andExpect(status().isBadRequest());
            body.put(field,0);send(json.writeValueAsString(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void evaluationOptionalFieldsDeserializeWhileRequiredPairsRemainsStrict() throws Exception {
        var value=read("{\"participantId\":\"participant\",\"pairs\":1,\"candidateStrategyVersionId\":\"strategy\"}",LabRequests.Evaluation.class);
        assertEquals(1,value.pairs());assertEquals("strategy",value.candidateStrategyVersionId());assertNull(value.candidateExperienceId());
        assertThrows(org.springframework.http.converter.HttpMessageNotReadableException.class,
                ()->read("{\"participantId\":\"participant\",\"candidateStrategyVersionId\":\"strategy\"}",LabRequests.Evaluation.class));
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        var principal=new AdminAuthService.AdminPrincipal("fixture-admin","fixture-session","FULL",AdminAuthService.AUTHORITY,"password",null,null,java.time.Instant.now().plusSeconds(60));
        return mvc.perform(post("/api/admin/lab/experiments").requestAttr(AdminAuthService.PRINCIPAL_ATTRIBUTE,principal)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String werewolfBody() throws Exception {
        var player=Map.of("modelKey","deepseek-flash","thinkingMode","ENABLED","thinkingEffort","LOW");
        var roster=new ArrayList<Map<String,Object>>();for(int i=0;i<9;i++) roster.add(Map.of("agentId","fixture-agent-"+(i%4),"selection",player));
        return json.writeValueAsString(Map.of("name","狼人杀共享策略先导","gameId","werewolf","learningMode","SHARED_STRATEGY",
                "playerCount",9,"targetGames",2,"budgetPoints",3000,"randomSeed",20261026L,"trainingCheckpointPoints",900L,
                "participants",roster,"coach",Map.of("modelKey","gpt-6.1-sol","thinkingMode","ENABLED","thinkingEffort","MAX")));
    }
    private void assertWerewolf(LabRequests.Experiment value) {
        assertEquals("werewolf",value.gameId());assertEquals(9,value.playerCount());assertEquals(9,value.participants().size());
        assertFalse(value.hasBlank());assertNull(value.wordPack());assertEquals("MAX",value.coach().thinkingEffort());
        assertEquals(20261026L,value.randomSeed());assertEquals(900L,value.trainingCheckpointPoints());
        assertEquals("LOW",value.participants().getFirst().selection().thinkingEffort());
    }
    @SuppressWarnings("unchecked")
    private <T> T read(String body,Class<T> type) throws java.io.IOException {
        var converter=(org.springframework.http.converter.HttpMessageConverter<T>)http.getMessageConverters().stream()
                .filter(value->value.canRead(type,MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        var input=new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return converter.read(type,input);
    }
}
