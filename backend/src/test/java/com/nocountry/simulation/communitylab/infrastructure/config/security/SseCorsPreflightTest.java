package com.nocountry.simulation.communitylab.infrastructure.config.security;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Cross-origin SSE reconnection: EventSource resends the last id in the Last-Event-ID request header,
 * which is not CORS-safelisted, so a dashboard on another origin needs it in the preflight allowlist
 * or every reconnection with replay is rejected.
 *
 * <p>Derivado de: spec 005 RF-07 / 006 RF-06 (replay con {@code Last-Event-ID}) + constitución Q3 (allowlist
 * explícita, nunca {@code *}) + revisión 2026-10-12.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("SSE CORS preflight")
class SseCorsPreflightTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @MockitoBean
    private JDA jda;

    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/discord/messages", "/api/v1/telegram/messages"})
    @DisplayName("Dado el origen permitido, cuando el navegador hace el preflight de la reconexión con Last-Event-ID, entonces se autoriza")
    void reconnectionHeaderIsAllowed(String stream) throws Exception {
        mockMvc.perform(options(stream)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "last-event-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsStringIgnoringCase("Last-Event-ID")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/discord/messages", "/api/v1/telegram/messages"})
    @DisplayName("Dado otro origen, cuando hace el mismo preflight, entonces se rechaza")
    void otherOriginIsStillRejected(String stream) throws Exception {
        mockMvc.perform(options(stream)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "last-event-id"))
                .andExpect(status().isForbidden());
    }
}
