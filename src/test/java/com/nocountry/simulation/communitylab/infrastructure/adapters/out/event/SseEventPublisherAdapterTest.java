package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage.GetMessagesProcessedDiscord;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.telegramMessage.GetMessageProcessedTelegram;

/**
 * Routing by source of the SSE outbound adapter (spec 005 dual-get).
 *
 * <p>Derivado de: spec 005 RF-07 + plan 005 §1 (SseEventPublisherAdapter "por
 * fuente, sin mezclar Discord/Telegram") + constitution v1.7-dual-get.
 * No Spring context: real adapter + real controllers over standalone MockMvc,
 * asserting on the buffered servlet response (the observable send behavior).
 */
@DisplayName("SseEventPublisherAdapter")
class SseEventPublisherAdapterTest {

    private SseEventPublisherAdapter adapter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        adapter = new SseEventPublisherAdapter();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GetMessagesProcessedDiscord(adapter),
                new GetMessageProcessedTelegram(adapter))
                .build();
    }

    @Test
    @DisplayName("Dado suscriptores en ambas fuentes, cuando se publica un asset TELEGRAM, entonces solo recibe el suscriptor Telegram")
    void publishesOnlyToSubscribersOfSameSource() throws Exception {
        // Dado un suscriptor por fuente
        MvcResult discord = mockMvc.perform(get("/api/v1/discord/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        MvcResult telegram = mockMvc.perform(get("/api/v1/telegram/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // Cuando se publica un asset de Telegram
        adapter.publish(telegramPost("tg-1"));

        // Entonces solo el stream Telegram recibe el evento
        assertThat(telegram.getResponse().getContentAsString())
                .contains("tg-1")
                .contains("TELEGRAM");
        assertThat(discord.getResponse().getContentAsString())
                .doesNotContain("tg-1");
    }

    @Test
    @DisplayName("Dado eventos previos, cuando se reconecta con Last-Event-ID, entonces el replay sale solo de la cola de su fuente")
    void replayUsesOnlyTheSourceQueue() throws Exception {
        // Dado dos eventos Telegram ya publicados sin suscriptores
        adapter.publish(telegramPost("tg-1"));
        adapter.publish(telegramPost("tg-2"));

        // Cuando se reconecta Telegram desde tg-1, entonces se repite solo tg-2
        MvcResult telegram = mockMvc.perform(get("/api/v1/telegram/messages")
                        .header("Last-Event-ID", "tg-1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(telegram.getResponse().getContentAsString())
                .contains("tg-2")
                .doesNotContain("tg-1");

        // Y cuando se reconecta Discord con un id Telegram, entonces no hay replay cruzado
        MvcResult discord = mockMvc.perform(get("/api/v1/discord/messages")
                        .header("Last-Event-ID", "tg-1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(discord.getResponse().getContentAsString())
                .doesNotContain("tg-2");
    }

    @Test
    @DisplayName("Dado publish sin suscriptores, cuando se publica, entonces no lanza y queda en replay")
    void publishWithoutSubscribersDoesNotThrow() {
        // Cuando se publica sin ningun suscriptor entonces no propaga excepcion
        assertThatCode(() -> adapter.publish(telegramPost("tg-1")))
                .doesNotThrowAnyException();

        // Entonces el evento queda disponible para replay posterior
        // (cubierto por replayUsesOnlyTheSourceQueue)
    }

    private ResponseClient telegramPost(String messageId) {
        return new ResponseClient(
                "author-name",
                "cos_dev",
                messageId,
                "batch-1",
                Sentiment.POSITIVO,
                Language.ES,
                MessageType.LOGRO,
                List.of("empleo", "java"),
                85,
                null,
                Instant.ofEpochSecond(1727000000),
                Source.TELEGRAM,
                Channels.LINKEDIN,
                null,
                "De la comunidad al primer empleo Java!",
                List.of("#ONE", "#Java"),
                "Comparte tu historia",
                false,
                1);
    }
}
