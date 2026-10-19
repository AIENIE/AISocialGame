package com.aisocialgame;

import com.aisocialgame.controller.admin.AdminAiModelConfigController;
import com.aisocialgame.dto.admin.*;
import com.aisocialgame.exception.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAiModelConfigControllerTest {
    private AiModelConfigService service;
    private MockMvc mvc;
    private static final String ROOT = "/api/admin/ai/model-config";
    private static final String BODY = "{\"modelKey\":\"model-x\",\"thinkingMode\":\"ENABLED\",\"thinkingEffort\":\"HIGH\",\"catalogVersion\":3,\"version\":2}";
    @BeforeEach void setup() {
        service = mock(AiModelConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminAiModelConfigController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void routesReadSyncAndSaveWithVersionsAndThinkingOptions() throws Exception {
        var catalog = new AdminAiModelConfigResponse(3,null,List.of(),List.of());
        when(service.getConfiguration()).thenReturn(catalog); when(service.sync()).thenReturn(catalog);
        var request = new AdminAiModelConfigRequest("model-x",ThinkingMode.ENABLED,"HIGH",3L,2L);
        var row = new AdminAiModelConfigResponse.FunctionView(AiFunctionCode.UNDERCOVER,"谁是卧底","model-x",ThinkingMode.ENABLED,"HIGH",3,"VALID","配置有效",false,"尚未启用");
        when(service.save(AiFunctionCode.UNDERCOVER,request)).thenReturn(row);
        mvc.perform(get(ROOT)).andExpect(status().isOk());
        mvc.perform(post(ROOT+"/sync")).andExpect(status().isOk());
        var response = mvc.perform(put(ROOT+"/functions/UNDERCOVER").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("HIGH",new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("thinkingEffort").asText());
        verify(service).save(AiFunctionCode.UNDERCOVER,request); verify(service).getConfiguration(); verify(service).sync();
    }
    @Test void rejectsUnknownFunctionsAndMissingVersionsBeforeSaving() throws Exception {
        mvc.perform(put(ROOT+"/functions/UNKNOWN").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(put(ROOT+"/functions/UNDERCOVER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelKey\":\"model-x\",\"thinkingMode\":\"DEFAULT\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void versionConflictsKeepTheirActionableErrorCode() throws Exception {
        when(service.save(any(),any())).thenThrow(new ApiException(HttpStatus.CONFLICT,"请重新读取","AI_MODEL_CONFIG_CONFLICT",Map.of()));
        var response = mvc.perform(put(ROOT+"/functions/UNDERCOVER").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertEquals("AI_MODEL_CONFIG_CONFLICT",new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("code").asText());
    }
}
