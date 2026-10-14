package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.application.port.in.LoadHistoryUseCase;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.adapters.out.event.SseEventPublisherAdapter;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * SSE subscription endpoint (no Spring context slice: full Boot context with MockMvc).
 *
 * <p>Derivado de: spec 004 RF-07 (GET text/event-stream con reconexión por
 * {@code Last-Event-ID}) + spec 008 RF-01/RF-06 (el controller pasa un historial perezoso de su fuente) + decisión single-GET (sin POSTs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GetMessagesProcessedDiscord")
class GetMessagesProcessedDiscordTest {

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

    @MockitoBean
    private SseEventPublisherAdapter publisher;

    @MockitoBean
    private LoadHistoryUseCase loadHistory;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Given no Last-Event-ID, when subscribed, then event-stream opens delegating with null")
    void opensEventStream() throws Exception {
        // Given a fresh emitter from the publisher
        List<SseEmitter> emitted = new ArrayList<>();
        when(publisher.subscribe(eq(Source.DISCORD), any(), any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(60_000L);
            emitted.add(emitter);
            return emitter;
        });

        // When subscribed without replay id, then async stream starts
        MvcResult started = mockMvc.perform(get("/api/v1/discord/messages")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // And completing the emitter resolves 200 + event-stream
        emitted.get(0).complete();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        verify(publisher).subscribe(eq(Source.DISCORD), isNull(), any());
    }

    @Test
    @DisplayName("Given Last-Event-ID, when subscribed, then it is forwarded for replay")
    void forwardsLastEventId() throws Exception {
        // Given a fresh emitter from the publisher
        List<SseEmitter> emitted = new ArrayList<>();
        when(publisher.subscribe(eq(Source.DISCORD), any(), any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(60_000L);
            emitted.add(emitter);
            return emitter;
        });

        // When subscribed with replay id, then async stream starts
        MvcResult started = mockMvc.perform(get("/api/v1/discord/messages")
                        .header("Last-Event-ID", "msg-7")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // And completing the emitter resolves 200 forwarding the id
        emitted.get(0).complete();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        verify(publisher).subscribe(eq(Source.DISCORD), eq("msg-7"), any());
    }

    @Test
    @DisplayName("Dado una suscripción, cuando el adapter pide el historial, entonces se carga el de esta fuente y solo entonces")
    @SuppressWarnings("unchecked")
    void passesALazyHistoryOfItsOwnSource() throws Exception {
        // Dado un adapter que devuelve un emitter y captura el supplier
        ArgumentCaptor<Supplier<History>> history = ArgumentCaptor.forClass(Supplier.class);
        List<SseEmitter> emitted = new ArrayList<>();
        when(publisher.subscribe(eq(Source.DISCORD), any(), history.capture())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(60_000L);
            emitted.add(emitter);
            return emitter;
        });
        when(loadHistory.load(Source.DISCORD)).thenReturn(History.empty());

        // Cuando se suscribe
        mockMvc.perform(get("/api/v1/discord/messages").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
        emitted.get(0).complete();

        // Entonces el controller no carga nada por su cuenta (la decisión replay/historial es del adapter)
        verify(loadHistory, never()).load(any());

        // Y cuando el adapter lo pide, se carga el historial de esta fuente, nunca el de la otra
        history.getValue().get();
        verify(loadHistory).load(Source.DISCORD);
        verify(loadHistory, never()).load(Source.TELEGRAM);
    }
}
