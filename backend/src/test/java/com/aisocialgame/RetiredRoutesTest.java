package com.aisocialgame;

import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class RetiredRoutesTest {
    @Autowired WebApplicationContext context;
    @MockitoBean AiGrpcClient rpc;

    @Test void retiredPathsAreMissingWhileTheCurrentCatalogRemainsAvailable() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        for (String path:List.of("/api/games/undercover/rooms/removed/speak",
                "/api/games/undercover/rooms/removed/vote", "/api/games/werewolf/rooms/removed/night-action",
                "/api/ai/embeddings", "/api/ai/ocr")) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/games")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        verifyNoInteractions(rpc);
    }
}
