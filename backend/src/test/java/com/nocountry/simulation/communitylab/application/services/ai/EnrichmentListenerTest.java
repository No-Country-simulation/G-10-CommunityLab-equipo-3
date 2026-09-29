package com.nocountry.simulation.communitylab.application.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.EnrichedCommentFromAi;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;

/**
 * Unit tests for the post-ingest orchestration (no Spring context).
 * Mockito only at port boundaries; domain records are real.
 * Derivado de: plan 003 §3 (tolerancia a fallos, fork SSE + buffer) +
 * spec 002 RF-04 (LLM_FALLBACK nunca 500) + spec 004 RF-06 (fallback
 * bufferizado y audible por SSE, post inválido descartado con LOG).
 */
@DisplayName("EnrichmentListener")
class EnrichmentListenerTest {

    private static final String BATCH_ID = "batch-1";
    private static final String MESSAGE_ID = "msg-1";

    private RequestToLLMProcess llm;
    private EnrichedCommentFromAi converter;
    private BufferPort buffer;
    private EventPublishPost publisher;
    private EnrichmentListener listener;

    @BeforeEach
    void setUp() {
        llm = mock(RequestToLLMProcess.class);
        converter = mock(EnrichedCommentFromAi.class);
        buffer = mock(BufferPort.class);
        publisher = mock(EventPublishPost.class);
        listener = new EnrichmentListener(llm, converter, buffer, publisher);
    }

    @Test
    @DisplayName("Given an event without message, when processed, then nothing runs and nothing is thrown")
    void nullMessageDiscarded() {
        // Given an event that carries no message
        IngestAcceptedEvent event = new IngestAcceptedEvent(null);

        // When processed then no exception escapes and no port is touched
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
        verifyNoInteractions(llm, converter, buffer, publisher);
    }

    @Test
    @DisplayName("Given an empty content, when processed, then nothing runs and nothing is thrown")
    void emptyContentDiscarded() {
        // Given a message whose content is empty
        IngestAcceptedEvent event = event("");

        // When processed then no exception escapes and no port is touched
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
        verifyNoInteractions(llm, converter, buffer, publisher);
    }

    @Test
    @DisplayName("Given a valid post, when processed, then it is published first and buffered second")
    void publishesAndBuffersValidPost() {
        // Given the LLM answers and the converter builds a valid post
        when(llm.processMessage(any())).thenReturn(validResponse());
        EnrichedComment post = validPost();
        when(converter.constructMessage(any(Comment.class), any(ResponseModel.class), eq(BATCH_ID)))
                .thenReturn(post);

        // When processed
        listener.on(event("Consegui mi primer empleo como dev Java, gracias comunidad"));

        // Then SSE first (fork A, ResponseClient mapping) and buffer second (fork B)
        var clientCaptor = ArgumentCaptor.forClass(ResponseClient.class);
        InOrder order = inOrder(publisher, buffer);
        order.verify(publisher).publish(clientCaptor.capture());
        order.verify(buffer).appendToBatch(post);
        assertThat(clientCaptor.getValue()).isEqualTo(expectedClient(post));
    }

    @Test
    @DisplayName("Given an LLM failure, when processed, then the fallback trace is published and buffered, never a 500")
    void flagsLlmFallbackOnLlmFailure() {
        // Given the LLM call fails
        when(llm.processMessage(any())).thenThrow(new RuntimeException("llm down"));
        EnrichedComment fallbackPost = fallbackPost();
        when(converter.constructMessage(any(Comment.class), any(ResponseModel.class), eq(BATCH_ID)))
                .thenReturn(fallbackPost);

        // When processed then no exception escapes
        assertThatCode(() -> listener.on(event("hola comunidad"))).doesNotThrowAnyException();

        // Then the converter received exactly the neutral fallback response
        var responseCaptor = ArgumentCaptor.forClass(ResponseModel.class);
        verify(converter).constructMessage(any(Comment.class), responseCaptor.capture(), eq(BATCH_ID));
        assertThat(responseCaptor.getValue()).isEqualTo(ResponseModel.fallback());

        // Then the flagged trace still goes out (auditable) and into the buffer
        var clientCaptor = ArgumentCaptor.forClass(ResponseClient.class);
        verify(publisher).publish(clientCaptor.capture());
        verify(buffer).appendToBatch(fallbackPost);
        assertThat(clientCaptor.getValue().flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(fallbackPost.flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
    }

    @Test
    @DisplayName("Given a copy with a company absent from the source, when processed, then the Guard blocks it")
    void guardBlocksHallucinatedPost() {
        // Given a valid-looking post whose copy invents a company the source never mentions
        when(llm.processMessage(any())).thenReturn(validResponse());
        when(converter.constructMessage(any(Comment.class), any(ResponseModel.class), eq(BATCH_ID)))
                .thenReturn(validPost("Alguien sabe como pasar la certificacion de google cloud sin pagar cursos"));

        // When processed
        listener.on(event("Consegui mi primer empleo como dev Java, gracias comunidad"));

        // Then the post never reaches SSE nor the buffer
        verifyNoInteractions(publisher, buffer);
    }

    @Test
    @DisplayName("Given an invalid non-fallback post, when processed, then it is silently discarded")
    void invalidPostDiscardedSilently() {
        // Given the converter rejects the post (record validation: invalid asset)
        when(llm.processMessage(any())).thenReturn(validResponse());
        when(converter.constructMessage(any(Comment.class), any(ResponseModel.class), eq(BATCH_ID)))
                .thenThrow(new InvalidAssetException("copy is required"));

        // When processed then no exception escapes and nothing is published or buffered
        assertThatCode(() -> listener.on(event("hola comunidad"))).doesNotThrowAnyException();
        verify(publisher, never()).publish(any());
        verify(buffer, never()).appendToBatch(any());
    }

    @Test
    @DisplayName("Given a fallback trace with empty copy, when processed, then it is still published (flag-first)")
    void fallbackPublishesEvenWithEmptyPost() {
        // Given an LLM failure mapped to a real fallback record (copy null, content null)
        when(llm.processMessage(any())).thenThrow(new RuntimeException("llm down"));
        EnrichedComment fallbackPost = new EnrichedComment(
                BATCH_ID, MESSAGE_ID, "channel-1", "author-1", "tester",
                null, Sentiment.NEUTRAL, null, MessageType.OTRO, List.of(), 0,
                EnrichedComment.LLM_FALLBACK, null, Instant.now(), Source.DISCORD,
                Channels.FAQ, null, null, List.of(), null);
        when(converter.constructMessage(any(Comment.class), any(ResponseModel.class), eq(BATCH_ID)))
                .thenReturn(fallbackPost);

        // When processed
        listener.on(event("hola comunidad"));

        // Then the flag-first check lets the auditable trace through
        var clientCaptor = ArgumentCaptor.forClass(ResponseClient.class);
        verify(publisher).publish(clientCaptor.capture());
        verify(buffer).appendToBatch(fallbackPost);
        assertThat(clientCaptor.getValue().flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
    }

    private IngestAcceptedEvent event(String content) {
        return new IngestAcceptedEvent(new ChannelMessage(
                BATCH_ID, MESSAGE_ID, "channel-1", "author-1", "tester",
                content, Instant.now(), false, Source.DISCORD));
    }

    private ResponseModel validResponse() {
        return new ResponseModel(
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Language.ES, Sentiment.POSITIVO, MessageType.LOGRO,
                List.of("empleo", "java"), 85,
                Channels.FAQ, "Primer empleo dev", "Conseguiste tu primer empleo como dev gracias a la comunidad?",
                List.of("#EmpleoTech"), null);
    }

    private EnrichedComment validPost() {
        return validPost("Conseguiste tu primer empleo como dev gracias a la comunidad?");
    }

    private EnrichedComment validPost(String copy) {
        return new EnrichedComment(
                BATCH_ID, MESSAGE_ID, "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo", "java"), 85, null, null, Instant.now(), Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", copy, List.of("#EmpleoTech"), null);
    }

    private EnrichedComment fallbackPost() {
        return new EnrichedComment(
                BATCH_ID, MESSAGE_ID, "channel-1", "author-1", "tester",
                null, Sentiment.NEUTRAL, null, MessageType.OTRO, List.of(), 0,
                EnrichedComment.LLM_FALLBACK, null, Instant.now(), Source.DISCORD,
                Channels.FAQ, null, null, List.of(), null);
    }

    // Mirror of the listener's EnrichedComment -> ResponseClient mapping; a record
    // so equality asserts the whole published payload in one line.
    private ResponseClient expectedClient(EnrichedComment source) {
        return new ResponseClient(
                source.authorName(), source.messageAuthor(), source.messageId(),
                source.messageBatchId(), source.sentiment(), source.language(),
                source.messageType(), source.topics(), source.relevance(),
                source.flag(), source.sentTime(), source.source(),
                source.channelPost(), source.titlePost(), source.outputContentProcessed(),
                source.hashtags(), source.cta());
    }
}
