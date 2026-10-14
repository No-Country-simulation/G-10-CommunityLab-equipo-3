package com.nocountry.simulation.communitylab.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.services.comment.telegram.IngestTelegramService;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tests for the Telegram use case.
 * Mirror of {@code IngestDiscordServiceTest}: same Given/When/Then style.
 *
 * <p>Derivado de: spec 005 RF-01,RF-04 + plan §1 (espejo Discord, sin SDKs).
 * No Spring context: BufferPort mock, events mock (puerto owned en 004).
 */
@DisplayName("IngestTelegramService")
class IngestTelegramServiceTest {

    private static final String FIXED_BATCH_ID = "batch-1";

    private IngestTelegramService useCase;
    private ApplicationEventPublisher events;
    private BufferPort buffer;

    @BeforeEach
    void setUp() {
        // Given un BufferPort mock con lote abierto estable para su fuente (sin Redis real).
        // La ingesta solo pide el batchId; el EnrichmentListener async bufferiza después.
        buffer = mock(BufferPort.class);
        when(buffer.getCurrentBatchId(Source.TELEGRAM)).thenReturn(FIXED_BATCH_ID);
        events = mock(ApplicationEventPublisher.class);
        useCase = new IngestTelegramService(buffer, events);
    }

    @Test
    @DisplayName("Dado comando valido, cuando ingiere, entonces retorna DTO normalizado TELEGRAM")
    void returnsNormalizedDto() {
        // Dado un comando valido con contenido acolchado
        IngestTelegramCommand command = new IngestTelegramCommand(
                1001,
                555L,
                777L,
                "cos_dev",
                "  sample content  ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // Cuando se ingiere
        var result = useCase.ingest(command);

        // Entonces DTO normalizado (trim, ids nativos a String, batchId de lote abierto)
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("sample content");
        assertThat(result.get().truncated()).isFalse();
        assertThat(result.get().messageId()).isEqualTo("1001");
        assertThat(result.get().channelId()).isEqualTo("555");
        assertThat(result.get().authorId()).isEqualTo("777");
        assertThat(result.get().source()).isEqualTo(Source.TELEGRAM);
        assertThat(result.get().batchId()).isEqualTo(FIXED_BATCH_ID);
        // Then el lote pedido es el de su propia fuente (lote por fuente, spec 006)
        verify(buffer).getCurrentBatchId(Source.TELEGRAM);
        verify(buffer, never()).getCurrentBatchId(Source.DISCORD);
        // Entonces se dispara 002 en background sin bloquear ingesta
        var published = forClass(IngestAcceptedEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().message().messageId()).isEqualTo("1001");
        assertThat(published.getValue().message().batchId()).isEqualTo(FIXED_BATCH_ID);
        assertThat(published.getValue().message().source()).isEqualTo(Source.TELEGRAM);
        // Entonces nada bufferizado en ingesta: el EnrichmentListener async lo hace despues
        verify(buffer, never()).appendToBatch(any());
    }

    @Test
    @DisplayName("Dado contenido vacio, cuando ingiere, entonces retorna empty sin evento")
    void discardsBlankMessage() {
        // Dado un comando solo-blancos
        IngestTelegramCommand command = new IngestTelegramCommand(
                1002,
                555L,
                777L,
                "cos_dev",
                "   ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // Cuando se ingiere entonces se descarta en silencio sin disparar 002
        assertThat(useCase.ingest(command)).isEmpty();
        verifyNoInteractions(events);
        verify(buffer, never()).appendToBatch(any());
    }

    @Test
    @DisplayName("Dado >2000 chars, cuando ingiere, entonces trunca a 2000 con flag")
    void truncatesLongMessage() {
        // Dado contenido de 3500 chars (RF-02: truncado uniforme 2000 + flag)
        String longContent = "a".repeat(3500);
        IngestTelegramCommand command = new IngestTelegramCommand(
                1003,
                555L,
                777L,
                "cos_dev",
                longContent,
                Instant.parse("2026-09-24T10:00:00Z"));

        // Cuando se ingiere
        var result = useCase.ingest(command);

        // Entonces truncado a 2000 con flag + batchId preservado
        assertThat(result).isPresent();
        assertThat(result.get().content()).hasSize(2000);
        assertThat(result.get().truncated()).isTrue();
        assertThat(result.get().batchId()).isEqualTo(FIXED_BATCH_ID);
        verify(buffer, never()).appendToBatch(any());
    }

    @Test
    @Timeout(2)
    @DisplayName("Dado comando valido, cuando ingiere, entonces retorna en <300ms (sin LLM)")
    void returnsWithin300ms() {
        // Dado comando valido minimo
        IngestTelegramCommand command = new IngestTelegramCommand(
                1004,
                555L,
                777L,
                "cos_dev",
                "hello",
                Instant.parse("2026-09-24T10:00:00Z"));

        // Cuando se mide latencia local sin LLM
        long startNanos = System.nanoTime();
        var result = useCase.ingest(command);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // Entonces presente y por debajo del presupuesto
        assertThat(result).isPresent();
        assertThat(elapsed.toMillis()).isLessThan(300);
    }
}
