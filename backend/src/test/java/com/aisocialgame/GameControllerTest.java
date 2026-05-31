package com.aisocialgame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiSocialGameApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldListGames() throws Exception {
        mockMvc.perform(get("/api/games").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void shouldExposePlannedGameContentMatrixWithTurtleSoupActive() throws Exception {
        mockMvc.perform(get("/api/games").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem("turtle_soup")))
                .andExpect(jsonPath("$[*].id", hasItem("mystery_case")))
                .andExpect(jsonPath("$[*].id", hasItem("secret_signal")))
                .andExpect(jsonPath("$[*].id", hasItem("mind_match")))
                .andExpect(jsonPath("$[*].id", hasItem("debate_arena")))
                .andExpect(jsonPath("$[*].id", hasItem("truth_or_bluff")))
                .andExpect(jsonPath("$[?(@.id=='turtle_soup')].status", contains("ACTIVE")))
                .andExpect(jsonPath("$[?(@.id=='turtle_soup')].engineBacked", contains(true)))
                .andExpect(jsonPath("$[?(@.id=='mystery_case')].status", contains("COMING_SOON")))
                .andExpect(jsonPath("$[?(@.id=='secret_signal')].status", contains("COMING_SOON")))
                .andExpect(jsonPath("$[?(@.id=='mind_match')].status", contains("COMING_SOON")))
                .andExpect(jsonPath("$[?(@.id=='debate_arena')].status", contains("COMING_SOON")))
                .andExpect(jsonPath("$[?(@.id=='truth_or_bluff')].status", contains("COMING_SOON")))
                .andExpect(jsonPath("$[?(@.id=='mystery_case')].engineBacked", contains(false)))
                .andExpect(jsonPath("$[?(@.id=='secret_signal')].tags[*]", contains("破冰", "潜伏", "短局")));
    }
}
