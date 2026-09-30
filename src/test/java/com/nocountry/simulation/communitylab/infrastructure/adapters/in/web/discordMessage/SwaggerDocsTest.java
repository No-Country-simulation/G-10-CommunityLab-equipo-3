package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Public API docs (no Spring context slice: full Boot context with MockMvc).
 *
 * <p>Derivado de: spec 004 RF-04 (Swagger documenta el único GET) + spec 005 RF-07
 * (Swagger documenta el GET Telegram) + constitution P3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SwaggerDocs")
class SwaggerDocsTest {

    // Why: the real JDA bean must never hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    // Why: the Telegram long-polling starter registers the bot against the real
    // API on context startup; mock the initializer so tests stay offline.
    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then the SSE endpoint is documented")
    void documentsSseEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/discord/messages'].get.summary")
                        .value("Subscribe to processed posts"))
                .andExpect(jsonPath("$.paths['/api/v1/discord/messages'].get.responses['200']")
                        .exists());
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then EnrichedComment schema exposes post fields")
    void documentsPostSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.EnrichedComment.properties.outputContentProcessed")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.EnrichedComment.properties.channelPost")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.EnrichedComment.properties.messageBatchId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.EnrichedComment.properties.hashtags")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.EnrichedComment.properties.sentiment")
                        .exists());
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then UI is served publicly")
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then the Telegram SSE endpoint is documented")
    void documentsTelegramSseEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/telegram/messages'].get.summary")
                        .value("Subscribe to processed Telegram posts"))
                .andExpect(jsonPath("$.paths['/api/v1/telegram/messages'].get.responses['200']")
                        .exists());
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then ResponseClient schema exposes Telegram post fields")
    void documentsTelegramPostSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.outputContentProcessed")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.source")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.messageBatchId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.hashtags")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.sentiment")
                        .exists());
    }
}
