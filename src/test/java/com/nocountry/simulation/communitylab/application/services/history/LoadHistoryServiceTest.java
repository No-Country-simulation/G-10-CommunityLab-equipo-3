package com.nocountry.simulation.communitylab.application.services.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.PackageIndex;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * History use case: gathers what a late subscriber must see (valid packages + messages not yet in OCI).
 * No Spring context; Mockito only at the {@link BufferPort} and {@link PackageIndex} boundaries.
 *
 * <p>Derivado de: spec 008 RF-01 (paquetes vigentes + pendientes + lote abierto, en orden cronológico),
 * RF-06 (por fuente), RF-07 (un fallo devuelve historial vacío, nunca rompe el stream) + plan 008 §1.1, §3
 * (orden de lectura abierto → pendientes → índice; envío pendientes antes que el abierto)
 * + spec 010 RF-04 (los pendientes se leen en una sola lectura).
 */
@DisplayName("LoadHistoryService")
@ExtendWith(OutputCaptureExtension.class)
class LoadHistoryServiceTest {

    private static final String PAR_URL = "https://par.example/p/SECRET-PAR-TOKEN/o/paquete.json";

    private BufferPort buffer;
    private PackageIndex index;
    private LoadHistoryService service;

    @BeforeEach
    void setUp() {
        buffer = mock(BufferPort.class);
        index = mock(PackageIndex.class);
        service = new LoadHistoryService(buffer, index);
    }

    @Test
    @DisplayName("Dado paquetes vigentes, un lote pendiente y el lote abierto, cuando se carga, entonces vienen los paquetes y los mensajes pendientes antes que los abiertos")
    void historyHasPackagesThenPendingThenOpenMessages() {
        // Dado un paquete en el índice, un lote sellado sin subir y dos mensajes en el lote abierto
        PackageCompleted pkg = pkg("b-1");
        when(index.active(eq(Source.TELEGRAM), any())).thenReturn(List.of(pkg));
        when(buffer.pendingBatches(Source.TELEGRAM)).thenReturn(Set.of("sealed-1"));
        when(buffer.readSealedAll(Source.TELEGRAM, Set.of("sealed-1"))).thenReturn(List.of(post("m-1")));
        when(buffer.readOpen(Source.TELEGRAM)).thenReturn(List.of(post("m-2"), post("m-3")));

        // Cuando se carga el historial
        History history = service.load(Source.TELEGRAM);

        // Entonces los paquetes tal cual y los mensajes en orden cronológico, ya en formato del evento vivo
        assertThat(history.packages()).containsExactly(pkg);
        assertThat(history.pending()).extracting(ResponseClient::messageId).containsExactly("m-1", "m-2", "m-3");
        assertThat(history.pending().getFirst()).isEqualTo(ResponseClient.from(post("m-1")));
    }

    @Test
    @DisplayName("Dado el montaje del historial, cuando se carga, entonces lee primero el lote abierto, luego los pendientes y por último el índice")
    void readsTheYoungestDataFirst() {
        // Dado un lote pendiente
        when(buffer.pendingBatches(Source.TELEGRAM)).thenReturn(Set.of("sealed-1"));

        // Cuando se carga
        service.load(Source.TELEGRAM);

        // Entonces un sellado o un flush entre lecturas duplica un mensaje en vez de perderlo (plan §3)
        InOrder order = inOrder(buffer, index);
        order.verify(buffer).readOpen(Source.TELEGRAM);
        order.verify(buffer).pendingBatches(Source.TELEGRAM);
        order.verify(buffer).readSealedAll(Source.TELEGRAM, Set.of("sealed-1"));
        order.verify(index).active(eq(Source.TELEGRAM), any());
    }

    @Test
    @DisplayName("Dado varios lotes pendientes, cuando se carga, entonces entran los mensajes de todos")
    void everyPendingBatchIsIncluded() {
        // Dado dos lotes sellados sin subir
        Set<String> pending = new LinkedHashSet<>(List.of("s-1", "s-2"));
        when(buffer.pendingBatches(Source.TELEGRAM)).thenReturn(pending);
        when(buffer.readSealedAll(Source.TELEGRAM, pending)).thenReturn(List.of(post("m-1"), post("m-2")));

        // Cuando se carga / Entonces ningún mensaje procesado se queda fuera del historial
        assertThat(service.load(Source.TELEGRAM).pending())
                .extracting(ResponseClient::messageId)
                .containsExactly("m-1", "m-2");
    }

    @Test
    @DisplayName("Dado varios lotes pendientes, cuando se carga, entonces se leen en una sola lectura y nunca uno por uno")
    void pendingBatchesAreReadInOneBulkRead() {
        // Dado tres lotes sellados sin subir
        Set<String> pending = new LinkedHashSet<>(List.of("s-1", "s-2", "s-3"));
        when(buffer.pendingBatches(Source.DISCORD)).thenReturn(pending);

        // Cuando se carga (cada reconexión SSE, spec 010 RF-04)
        service.load(Source.DISCORD);

        // Entonces el coste no crece con el número de lotes
        verify(buffer).readSealedAll(Source.DISCORD, pending);
        verify(buffer, never()).readSealed(any(), anyString());
    }

    @Test
    @DisplayName("Dado el índice, cuando se carga, entonces se le pide lo vigente en este momento")
    void indexIsAskedForWhatIsValidNow() {
        // Cuando se carga
        Instant before = Instant.now();
        service.load(Source.TELEGRAM);
        Instant after = Instant.now();

        // Entonces el corte de vigencia es la hora real de la suscripción
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(index).active(eq(Source.TELEGRAM), now.capture());
        assertThat(now.getValue()).isBetween(before, after);
    }

    @Test
    @DisplayName("Dado nada guardado, cuando se carga, entonces el historial está vacío")
    void nothingStoredGivesAnEmptyHistory() {
        // Cuando se carga sin datos (los mocks devuelven colecciones vacías)
        History history = service.load(Source.TELEGRAM);

        // Entonces sin paquetes ni mensajes
        assertThat(history.packages()).isEmpty();
        assertThat(history.pending()).isEmpty();
    }

    @Test
    @DisplayName("Dado una fuente, cuando se carga, entonces nunca se consultan los datos de la otra")
    void loadsOnlyTheRequestedSource() {
        // Cuando se carga Discord
        service.load(Source.DISCORD);

        // Entonces nada de Telegram (dual-get)
        verify(buffer, never()).readOpen(Source.TELEGRAM);
        verify(buffer, never()).pendingBatches(Source.TELEGRAM);
        verify(buffer, never()).readSealed(eq(Source.TELEGRAM), anyString());
        verify(index, never()).active(eq(Source.TELEGRAM), any());
    }

    @Test
    @DisplayName("Dado un puerto que lanza, cuando se carga, entonces devuelve historial vacío y el log no lleva la PAR")
    void failureGivesAnEmptyHistory(CapturedOutput output) {
        // Dado un índice con datos pero un buffer que revienta
        when(index.active(eq(Source.TELEGRAM), any())).thenReturn(List.of(pkg("b-1")));
        when(buffer.readOpen(Source.TELEGRAM)).thenThrow(new IllegalStateException("redis broken"));

        // Cuando se carga
        History history = service.load(Source.TELEGRAM);

        // Entonces el stream se abrirá igual con historial vacío (RF-07)
        assertThat(history.packages()).isEmpty();
        assertThat(history.pending()).isEmpty();
        assertThat(output.getOut()).contains("history load failed: source=TELEGRAM").doesNotContain("SECRET-PAR-TOKEN");
    }

    // ---------- fixtures ----------

    private static PackageCompleted pkg(String batchId) {
        return new PackageCompleted(batchId, Source.TELEGRAM, 3, PAR_URL, Instant.parse("2026-10-17T10:00:00Z"));
    }

    private static EnrichedComment post(String messageId) {
        return new EnrichedComment(
                "ingest-batch", messageId, "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, null, Instant.parse("2026-10-06T10:00:00Z"), Source.TELEGRAM,
                Channels.FAQ, "Primer empleo dev",
                "Consegui mi primer empleo como dev Java gracias a la comunidad",
                List.of("#EmpleoTech"), null, false, ApprovalStatus.IN_REVIEW, 1, null, null);
    }
}
