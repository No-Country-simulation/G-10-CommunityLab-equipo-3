package com.nocountry.simulation.communitylab.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationEventPublisher;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.enums.Source;

/**
 * Tests for the Discord use case.
 * Mirror of {@code IngestDiscordService}: same package as the source.
 *
 * <p>Derivado de: spec 001 RF-01a,RF-02,RF-04 + RNF-01 (p95 &lt;300ms) + plan §1,§3.
 * No Spring context: BufferPort se sustituye con fake (puerto owned en 004).
 */
@DisplayName("IngestDiscordService")
class IngestDiscordServiceTest {

    private static final String FIXED_BATCH_ID = "batch-1";

    private IngestDiscordService useCase;
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        // Given un BufferPort fake con lote abierto estable (no Redis real en 001)
        BufferPort fakeBuffer = () -> FIXED_BATCH_ID;
        events = mock(ApplicationEventPublisher.class);
        useCase = new IngestDiscordService(fakeBuffer, events);
    }

    @Test
    @DisplayName("Given valid command, when ingested, then it returns the normalized DTO")
    void returnsNormalizedDto() {
        // Given a valid command with padded content
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-1",
                "listen-123",
                "author-1",
                "author-name",
                "  sample content  ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // When ingested
        var result = useCase.ingest(command);

        // Then normalized DTO (trimmed, trace preserved, batchId from lote abierto)
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("sample content");
        assertThat(result.get().truncated()).isFalse();
        assertThat(result.get().channelId()).isEqualTo("listen-123");
        assertThat(result.get().source()).isEqualTo(Source.DISCORD);
        assertThat(result.get().batchId()).isEqualTo(FIXED_BATCH_ID);
        assertThat(result.get().messageId()).isEqualTo("msg-1");
        // Then 002 is triggered in background without blocking ingest
        var published = forClass(IngestAcceptedEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().message().messageId()).isEqualTo("msg-1");
        assertThat(published.getValue().message().batchId()).isEqualTo(FIXED_BATCH_ID);
    }

    @Test
    @DisplayName("Given blank content, when ingested, then it returns empty")
    void discardsBlankMessage() {
        // Given a blank-only command
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-2",
                "listen-123",
                "author-1",
                "author-name",
                "   ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // When ingested then discarded silently without triggering 002
        assertThat(useCase.ingest(command)).isEmpty();
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Given >2000 chars, when ingested, then it truncates to 2000 with flag")
    void truncatesLongMessage() {
        // Given contenido de 3500 chars (RF-02/RF-03: truncado uniforme 2000 + flag)
        String longContent = "a".repeat(3500);
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-3",
                "listen-123",
                "author-1",
                "author-name",
                longContent,
                Instant.parse("2026-09-24T10:00:00Z"));

        // When ingested
        var result = useCase.ingest(command);

        // Then truncado a 2000 con flag + batchId preservado
        assertThat(result).isPresent();
        assertThat(result.get().content()).hasSize(2000);
        assertThat(result.get().truncated()).isTrue();
        assertThat(result.get().batchId()).isEqualTo(FIXED_BATCH_ID);
    }

    @Test
    @Timeout(2)
    @DisplayName("Given valid command, when ingested, then it returns in <300ms (RNF-01 sin LLM)")
    void returnsWithin300ms() {
        // Given comando válido mínimo
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-4",
                "listen-123",
                "author-1",
                "author-name",
                "hello",
                Instant.parse("2026-09-24T10:00:00Z"));

        // When se mide latencia local sin LLM
        long startNanos = System.nanoTime();
        var result = useCase.ingest(command);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // Then presente y por debajo del presupuesto RNF-01
        assertThat(result).isPresent();
        assertThat(elapsed.toMillis()).isLessThan(300);
    }
}
