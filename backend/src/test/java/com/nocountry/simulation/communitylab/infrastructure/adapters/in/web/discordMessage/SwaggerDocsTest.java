package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Public API docs (no Spring context slice: full Boot context with MockMvc).
 *
 * <p>Derivado de: spec 004 RF-04 (Swagger documenta el único GET) + spec 005 RF-07
 * (Swagger documenta el GET Telegram) + spec 006 RF-06 + TASK-006-04 (evento {@code package.completed}
 * en ambos GET SSE, sin {@code GET /packages*}) + spec 007 RF-02 (PATCH de revisión) + spec 008 RF-04
 * (historial al conectar y {@code snapshot.completed} documentados en ambos GET SSE) + constitution P3.
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

    // Why: TelegramBotProperties binds a long at startup; the Binder does not
    // resolve YAML placeholders, so tests must supply a numeric value.
    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

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
    @DisplayName("Given started app, when GET /v3/api-docs, then ResponseClient schema exposes post fields")
    void documentsPostSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.outputContentProcessed")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.channelPost")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.messageBatchId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.hashtags")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ResponseClient.properties.source")
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

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then both SSE streams document package.completed with its PAR payload")
    void documentsPackageCompletedOnBothStreams() throws Exception {
        for (String source : new String[] {"discord", "telegram"}) {
            String get = "$.paths['/api/v1/" + source + "/messages'].get";
            String example = get + ".responses['200'].content['text/event-stream'].examples['package.completed'].value";
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(get + ".description").value(containsString("package.completed")))
                    .andExpect(jsonPath(get + ".responses['200'].content['text/event-stream'].examples['asset.created']")
                            .exists())
                    // every new post starts pending review (spec 007 RF-01, enmienda 2026-10-13)
                    .andExpect(jsonPath(get + ".responses['200'].content['text/event-stream'].examples['asset.created'].value.revised")
                            .value(false))
                    .andExpect(jsonPath(example + ".type").value("package.completed"))
                    .andExpect(jsonPath(example + ".batchId").exists())
                    .andExpect(jsonPath(example + ".payload.assetsCount").exists())
                    .andExpect(jsonPath(example + ".payload.expiresAt").exists())
                    .andExpect(jsonPath(example + ".payload.parUrlBase").value(containsString("paquetes/" + source + "/")))
                    // the notice carries no content: the package is read from OCI through the PAR
                    .andExpect(jsonPath(example + ".payload.enriched").doesNotExist());
        }
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then the revision PATCH is documented with its source choices")
    void documentsRevisionEndpoint() throws Exception {
        String path = "$.paths['/api/v1/{source}/packages/{batchId}/messages/{messageId}'].patch";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").value("Revise a post"))
                .andExpect(jsonPath(path + ".responses['200']").exists())
                .andExpect(jsonPath(path + ".responses['202']").doesNotExist())
                .andExpect(jsonPath(path + ".responses['400']").exists())
                .andExpect(jsonPath(path + ".responses['404']").exists())
                .andExpect(jsonPath(path + ".responses['409']").exists())
                .andExpect(jsonPath(path + ".responses['413']").exists())
                .andExpect(jsonPath(path + ".responses['422']").exists())
                .andExpect(jsonPath(path + ".responses['502']").exists())
                .andExpect(jsonPath(path + ".responses['503']").exists())
                .andExpect(jsonPath(path + ".parameters[?(@.name == 'source')].schema.enum[*]",
                        containsInAnyOrder("discord", "telegram")));
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then RevisePostResponse carries the saved version, approval and review mark")
    void documentsRevisionResponseSchema() throws Exception {
        String schema = "$.components.schemas.RevisePostResponse.properties";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(schema + ".batchId").exists())
                .andExpect(jsonPath(schema + ".messageId").exists())
                .andExpect(jsonPath(schema + ".versionMessage").exists())
                .andExpect(jsonPath(schema + ".approved").exists())
                .andExpect(jsonPath(schema + ".revised").exists())
                // internal flag of the use case, not part of the HTTP contract
                .andExpect(jsonPath(schema + ".changed").doesNotExist());
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then both SSE streams document message.revised without content (spec 007 RF-08)")
    void documentsMessageRevisedOnBothStreams() throws Exception {
        for (String source : new String[] {"discord", "telegram"}) {
            String get = "$.paths['/api/v1/" + source + "/messages'].get";
            String example = get + ".responses['200'].content['text/event-stream'].examples['message.revised'].value";
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(get + ".description").value(containsString("message.revised")))
                    .andExpect(jsonPath(example + ".type").value("message.revised"))
                    .andExpect(jsonPath(example + ".batchId").exists())
                    .andExpect(jsonPath(example + ".payload.messageId").exists())
                    .andExpect(jsonPath(example + ".payload.versionMessage").exists())
                    .andExpect(jsonPath(example + ".payload.approved").exists())
                    .andExpect(jsonPath(example + ".payload.revised").value(true))
                    // the notice carries no content: the post is read through the PAR (RF-07)
                    .andExpect(jsonPath(example + ".payload.outputContentProcessed").doesNotExist());
        }
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then both SSE streams document the history block and snapshot.completed (spec 008)")
    void documentsTheHistoryBlockOnBothStreams() throws Exception {
        for (String source : new String[] {"discord", "telegram"}) {
            String get = "$.paths['/api/v1/" + source + "/messages'].get";
            String example = get + ".responses['200'].content['text/event-stream'].examples['snapshot.completed'].value";
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(get + ".description").value(containsString("snapshot.completed")))
                    .andExpect(jsonPath(get + ".description").value(containsString("upsert by id")))
                    .andExpect(jsonPath(example + ".type").value("snapshot.completed"))
                    .andExpect(jsonPath(example + ".packages").exists())
                    .andExpect(jsonPath(example + ".pending").exists());
        }
    }

    @Test
    @DisplayName("Given started app, when GET /v3/api-docs, then RevisePostRequest exposes only the editable contract")
    void documentsRevisionRequestSchema() throws Exception {
        String schema = "$.components.schemas.RevisePostRequest.properties";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(schema + ".expectedVersion").exists())
                .andExpect(jsonPath(schema + ".approved").exists())
                .andExpect(jsonPath(schema + ".revised").exists())
                .andExpect(jsonPath(schema + ".outputContentProcessed").exists())
                .andExpect(jsonPath(schema + ".channelPost").exists())
                // internal plumbing must not leak into the public contract
                .andExpect(jsonPath(schema + ".unknownFields").doesNotExist())
                .andExpect(jsonPath(schema + ".anyChange").doesNotExist())
                .andExpect(jsonPath(schema + ".onlyEditableFields").doesNotExist())
                .andExpect(jsonPath(schema + ".revisedOnlyTrue").doesNotExist())
                // the AI analysis is not editable, so it is not part of the request
                .andExpect(jsonPath(schema + ".sentiment").doesNotExist())
                .andExpect(jsonPath(schema + ".relevance").doesNotExist());
    }
}
