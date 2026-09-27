package com.nocountry.simulation.communitylab.application.services.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
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

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.EnrichedCommentFromAi;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Unit tests for the async enrichment consumer (no Spring context: direct
 * call to {@code on}, the {@code @Async} proxy only applies at runtime).
 *
 * <p>Derivado de: spec 002 RF-01/RF-02/RF-04 (anonimato solo ante el LLM,
 * tolerancia a fallos sin 500) + plan 002 §1 (background tras 001).
 */
@DisplayName("EnrichmentListener")
class EnrichmentListenerTest {

    private RequestToLLMProcess llm;
    private EnrichedCommentFromAi converter;
    private BufferPort buffer;

    private EnrichmentListener listener;

    @BeforeEach
    void setUp() {
        llm = mock(RequestToLLMProcess.class);
        converter = mock(EnrichedCommentFromAi.class);
        buffer = mock(BufferPort.class);
        listener = new EnrichmentListener(llm, converter, buffer);
    }

    private static IngestAcceptedEvent event(String content) {
        Comment comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name",
                content, Instant.parse("2026-09-24T10:00:00Z"), Source.DISCORD);
        return new IngestAcceptedEvent(ChannelMessage.from(comment, "batch-1"));
    }

    private static ResponseModel response() {
        return new ResponseModel("processed", Language.ES, Sentiment.NEUTRAL,
                MessageType.OTRO, List.of("saludo"), 30);
    }

    private static EnrichedComment enriched() {
        return new EnrichedComment(
                "batch-1", "msg-1", "listen-123", "author-1", "author-name",
                "processed", Sentiment.NEUTRAL, Language.ES, MessageType.OTRO,
                List.of("saludo"), 30, null, EnrichedComment.PROMPT_VERSION,
                Instant.parse("2026-09-24T10:00:00Z"), Source.DISCORD);
    }

    @Test
    @DisplayName("Dado evento valido, cuando llega, entonces pide al LLM solo con el texto y bufferiza")
    void enrichesAndBuffers() {
        // Dado un evento normalizado con trazabilidad (Comment.create ya trimea en 001)
        IngestAcceptedEvent accepted = event("hello");
        when(llm.processMessage(new RequestToLLM("hello"))).thenReturn(response());
        when(converter.constructMessage(
                org.mockito.ArgumentMatchers.any(Comment.class),
                org.mockito.ArgumentMatchers.eq(response()),
                org.mockito.ArgumentMatchers.eq("batch-1"))).thenReturn(enriched());

        // Cuando se consume (sync en test, @Async solo en runtime)
        assertThatCode(() -> listener.on(accepted)).doesNotThrowAnyException();

        // Entonces al LLM solo va el texto (anonimato) y el buffer recibe el enriquecido
        var requestCaptor = forClass(RequestToLLM.class);
        verify(llm).processMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().message()).isEqualTo("hello");
        verify(converter).constructMessage(
                org.mockito.ArgumentMatchers.any(Comment.class),
                org.mockito.ArgumentMatchers.eq(response()),
                org.mockito.ArgumentMatchers.eq("batch-1"));
        // EnrichedComment es record (equals por valor), pero se verifica por messageId trazado
        var bufferedCaptor = forClass(EnrichedComment.class);
        verify(buffer).appendToBatch(bufferedCaptor.capture());
        assertThat(bufferedCaptor.getValue().messageId()).isEqualTo("msg-1");
    }

    @Test
    @DisplayName("Dado contenido vacio, cuando llega, entonces aborta sin LLM ni buffer ni throw")
    void discardsEmptyContent() {
        // Dado un evento con contenido vacio (filtro previo roto o carrera)
        Comment comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name",
                "hello", Instant.parse("2026-09-24T10:00:00Z"), Source.DISCORD);
        ChannelMessage empty = new ChannelMessage(
                "batch-1", "msg-1", "listen-123", "author-1", "author-name",
                "", comment.sentTime(), false, Source.DISCORD);

        // Cuando se consume entonces aborta silencioso (nunca 500)
        assertThatCode(() -> listener.on(new IngestAcceptedEvent(empty))).doesNotThrowAnyException();

        // Entonces sin LLM, sin conversion, sin buffer
        verifyNoInteractions(llm, converter, buffer);
    }

    @Test
    @DisplayName("Dado fallo del LLM, cuando llega, entonces bufferea fallback con flag sin throw")
    void buffersFallbackOnLlmFailure() {
        // Dado un evento valido pero el LLM cae
        IngestAcceptedEvent accepted = event("hello");
        when(llm.processMessage(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("llm down"));
        EnrichedComment fallback = new EnrichedComment(
                "batch-1", "msg-1", "listen-123", "author-1", "author-name",
                null, Sentiment.NEUTRAL, null, MessageType.OTRO,
                List.of(), 0, EnrichedComment.LLM_FALLBACK, EnrichedComment.PROMPT_VERSION,
                Instant.parse("2026-09-24T10:00:00Z"), Source.DISCORD);
        when(converter.constructMessage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(fallback);

        // Cuando se consume entonces no lanza (nunca 500)
        assertThatCode(() -> listener.on(accepted)).doesNotThrowAnyException();

        // Entonces el fallo deja rastro tipado en el buffer
        var bufferedCaptor = forClass(EnrichedComment.class);
        verify(buffer).appendToBatch(bufferedCaptor.capture());
        assertThat(bufferedCaptor.getValue().flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(bufferedCaptor.getValue().relevance()).isZero();
    }
}
