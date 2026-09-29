package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.nocountry.simulation.communitylab.infrastructure.adapters.out.event.SseEventPublisherAdapter;

import net.dv8tion.jda.api.JDA;

/**
 * SSE subscription endpoint (no Spring context slice: full Boot context with MockMvc).
 *
 * <p>Derivado de: spec 004 RF-07 (GET text/event-stream con reconexión por
 * {@code Last-Event-ID}) + decisión single-GET (sin POSTs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GetMessagesProcessedDiscord")
class GetMessagesProcessedDiscordTest {

    // Why: the real JDA bean must never hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    @MockitoBean
    private SseEventPublisherAdapter publisher;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Given no Last-Event-ID, when subscribed, then event-stream opens delegating with null")
    void opensEventStream() throws Exception {
        // Given a fresh emitter from the publisher
        List<SseEmitter> emitted = new ArrayList<>();
        when(publisher.subscribe(any())).thenAnswer(invocation -> {
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
        verify(publisher).subscribe(null);
    }

    @Test
    @DisplayName("Given Last-Event-ID, when subscribed, then it is forwarded for replay")
    void forwardsLastEventId() throws Exception {
        // Given a fresh emitter from the publisher
        List<SseEmitter> emitted = new ArrayList<>();
        when(publisher.subscribe(any())).thenAnswer(invocation -> {
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
        verify(publisher).subscribe("msg-7");
    }
}
