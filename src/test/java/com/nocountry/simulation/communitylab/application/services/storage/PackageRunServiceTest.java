package com.nocountry.simulation.communitylab.application.services.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Flush orchestration per source: size trigger, hourly trigger, retry of pending
 * sealed batches and per-source locking. No Spring context; Mockito only at the
 * {@link BufferPort} and {@link ArtifactStore} boundaries.
 *
 * <p>Derivado de: spec 006 RF-01 (flush por tamaño, vacío no se sube) + RF-05
 * (idempotencia, nunca perder el lote) + regla nueva lote por fuente con flush
 * horario si {@code count >= min-messages} + plan 006 §3 (lock por fuente).
 */
@DisplayName("PackageRunService")
class PackageRunServiceTest {

    private static final long MAX_BYTES = 1_000L;
    private static final long MIN_MESSAGES = 5L;

    private BufferPort buffer;
    private ArtifactStore store;
    private PackageRunService service;

    @BeforeEach
    void setUp() {
        buffer = mock(BufferPort.class);
        store = mock(ArtifactStore.class);
        service = new PackageRunService(buffer, store, MAX_BYTES, MIN_MESSAGES);
    }

    // ---------- size trigger ----------

    @Test
    @DisplayName("Dado un lote bajo el límite de bytes, cuando llega un append, entonces no se toca el buffer ni el store")
    void belowMaxBytesDoesNothing() {
        // Cuando el lote reporta 1 byte menos que el límite
        service.flushIfFull(Source.DISCORD, MAX_BYTES - 1);

        // Entonces no hay flush (ni siquiera reintento de pendientes en el path caliente)
        verifyNoInteractions(buffer, store);
    }

    @Test
    @DisplayName("Dado un lote que alcanza exactamente el límite, cuando llega un append, entonces se sella, sube y descarta")
    void reachingMaxBytesSealsUploadsAndDiscards() {
        // Dado un lote DISCORD sellable con 2 registros y un store que confirma
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD), post("m-2", Source.DISCORD));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando el append deja el lote justo en el límite (>=, no >)
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces 1 paquete del lote sellado y luego limpieza en Redis
        ArgumentCaptor<AssetPackage> pkg = ArgumentCaptor.forClass(AssetPackage.class);
        InOrder order = inOrder(buffer, store);
        order.verify(buffer).sealCurrentBatch(Source.DISCORD);
        order.verify(buffer).readSealed(Source.DISCORD, "b-1");
        order.verify(store).save(pkg.capture());
        order.verify(buffer).discardSealed(Source.DISCORD, "b-1");
        assertThat(pkg.getValue().batchId()).isEqualTo("b-1");
        assertThat(pkg.getValue().source()).isEqualTo(Source.DISCORD);
        assertThat(pkg.getValue().enriched()).hasSize(2);
    }

    @Test
    @DisplayName("Dado un flush de DISCORD, cuando corre, entonces nunca toca el lote de TELEGRAM")
    void sizeFlushIsolatedBySource() {
        // Dado un lote DISCORD lleno
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando se dispara el flush por tamaño de DISCORD
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces ninguna operación sobre TELEGRAM
        verify(buffer, never()).pendingBatches(Source.TELEGRAM);
        verify(buffer, never()).sealCurrentBatch(Source.TELEGRAM);
        verify(buffer, never()).readSealed(eq(Source.TELEGRAM), anyString());
        verify(buffer, never()).discardSealed(eq(Source.TELEGRAM), anyString());
    }

    // ---------- hourly trigger ----------

    @Test
    @DisplayName("Dado un lote con 4 mensajes, cuando corre el tick horario, entonces no se sella")
    void hourlyTickBelowMinMessagesDoesNotSeal() {
        // Dado ambos lotes con menos del mínimo
        when(buffer.countMessages(Source.DISCORD)).thenReturn(MIN_MESSAGES - 1);
        when(buffer.countMessages(Source.TELEGRAM)).thenReturn(0L);

        // Cuando corre el tick
        service.flushScheduled();

        // Entonces nada se sella ni se sube
        verify(buffer, never()).sealCurrentBatch(any());
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("Dado un lote con 5 mensajes, cuando corre el tick horario, entonces se sella y sube aunque no llegue a 900KB")
    void hourlyTickAtMinMessagesSealsAndUploads() {
        // Dado DISCORD con exactamente el mínimo y TELEGRAM vacío
        when(buffer.countMessages(Source.DISCORD)).thenReturn(MIN_MESSAGES);
        when(buffer.countMessages(Source.TELEGRAM)).thenReturn(0L);
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando corre el tick
        service.flushScheduled();

        // Entonces solo DISCORD se sella, sube y descarta
        verify(buffer).sealCurrentBatch(Source.DISCORD);
        verify(store).save(any());
        verify(buffer).discardSealed(Source.DISCORD, "b-1");
        verify(buffer, never()).sealCurrentBatch(Source.TELEGRAM);
    }

    @Test
    @DisplayName("Dado ambas fuentes con suficientes mensajes, cuando corre el tick, entonces cada una sube su propio paquete")
    void hourlyTickUploadsOnePackagePerSource() {
        // Dado DISCORD y TELEGRAM llenos
        when(buffer.countMessages(any())).thenReturn(MIN_MESSAGES);
        sealable(Source.DISCORD, "b-d", post("m-1", Source.DISCORD));
        sealable(Source.TELEGRAM, "b-t", post("m-2", Source.TELEGRAM));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando corre el tick
        service.flushScheduled();

        // Entonces 2 paquetes, uno por fuente, nunca mezclados
        ArgumentCaptor<AssetPackage> pkgs = ArgumentCaptor.forClass(AssetPackage.class);
        verify(store, times(2)).save(pkgs.capture());
        assertThat(pkgs.getAllValues())
                .extracting(AssetPackage::source, AssetPackage::batchId)
                .containsExactlyInAnyOrder(
                        tuple(Source.DISCORD, "b-d"),
                        tuple(Source.TELEGRAM, "b-t"));
    }

    @Test
    @DisplayName("Dado un lote sellado pendiente, cuando corre el tick con pocos mensajes, entonces igual se reintenta")
    void hourlyTickRetriesPendingEvenBelowMinMessages() {
        // Dado un lote pendiente de un fallo anterior y un lote abierto casi vacío
        when(buffer.countMessages(Source.DISCORD)).thenReturn(1L);
        when(buffer.pendingBatches(Source.DISCORD)).thenReturn(Set.of("old"));
        when(buffer.readSealed(Source.DISCORD, "old")).thenReturn(List.of(post("m-1", Source.DISCORD)));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando corre el tick
        service.flushScheduled();

        // Entonces el pendiente se sube y descarta, sin sellar el lote abierto
        verify(buffer).discardSealed(Source.DISCORD, "old");
        verify(buffer, never()).sealCurrentBatch(Source.DISCORD);
    }

    @Test
    @DisplayName("Dado un pendiente y un lote lleno, cuando se hace flush, entonces el pendiente sube antes que el nuevo")
    void pendingUploadedBeforeNewlySealed() {
        // Dado un pendiente viejo y un lote nuevo sellable
        when(buffer.pendingBatches(Source.DISCORD)).thenReturn(Set.of("old"));
        when(buffer.readSealed(Source.DISCORD, "old")).thenReturn(List.of(post("m-1", Source.DISCORD)));
        sealable(Source.DISCORD, "new", post("m-2", Source.DISCORD));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando se dispara por tamaño
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces orden: pendiente viejo -> sellado -> nuevo
        InOrder order = inOrder(buffer);
        order.verify(buffer).discardSealed(Source.DISCORD, "old");
        order.verify(buffer).sealCurrentBatch(Source.DISCORD);
        order.verify(buffer).discardSealed(Source.DISCORD, "new");
    }

    @Test
    @DisplayName("Dado que DISCORD falla con una excepción, cuando corre el tick, entonces TELEGRAM igual se procesa")
    void hourlyTickFailureInOneSourceDoesNotBlockTheOther() {
        // Dado DISCORD con Redis roto (excepción no prevista) y TELEGRAM lleno
        when(buffer.countMessages(Source.DISCORD)).thenThrow(new IllegalStateException("redis broken"));
        when(buffer.countMessages(Source.TELEGRAM)).thenReturn(MIN_MESSAGES);
        sealable(Source.TELEGRAM, "b-t", post("m-1", Source.TELEGRAM));
        when(store.save(any())).thenReturn(new StoreResult(true, false));

        // Cuando corre el tick, nada escapa al scheduler
        assertThatCode(() -> service.flushScheduled()).doesNotThrowAnyException();

        // Entonces el fallo de una fuente no le cuesta el tick a la otra
        verify(buffer).discardSealed(Source.TELEGRAM, "b-t");
    }

    // ---------- never lose data ----------

    @Test
    @DisplayName("Dado un store que responde fallo (OCI_NOT_CONFIGURED), cuando se sube, entonces el lote queda pendiente")
    void storeFailureKeepsBatchPending() {
        // Dado un lote sellable y un store no configurado
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenReturn(StoreResult.failed());

        // Cuando se hace flush
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces no se borra: el próximo tick lo reintenta
        verify(store).save(any());
        verify(buffer, never()).discardSealed(any(), anyString());
    }

    @Test
    @DisplayName("Dado un store que lanza excepción, cuando se sube, entonces no escapa y el lote queda pendiente")
    void storeExceptionKeepsBatchPending() {
        // Dado un store que explota
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenThrow(new RuntimeException("oci down"));

        // Cuando se hace flush, nada escapa al listener async
        assertThatCode(() -> service.flushIfFull(Source.DISCORD, MAX_BYTES)).doesNotThrowAnyException();

        // Entonces el lote sigue pendiente
        verify(buffer, never()).discardSealed(any(), anyString());
    }

    @Test
    @DisplayName("Dado un lote sellado ilegible (vacío), cuando se sube, entonces no se llama al store ni se descarta")
    void unreadableSealedBatchIsKept() {
        // Dado un lote sellado cuya lectura vuelve vacía (p.ej. Redis caído)
        when(buffer.sealCurrentBatch(Source.DISCORD)).thenReturn(Optional.of("b-1"));
        when(buffer.readSealed(Source.DISCORD, "b-1")).thenReturn(List.of());

        // Cuando se hace flush
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces ante la duda no se sube un paquete vacío ni se borra nada
        verifyNoInteractions(store);
        verify(buffer, never()).discardSealed(any(), anyString());
    }

    @Test
    @DisplayName("Dado que no hay nada que sellar, cuando se hace flush, entonces no se llama al store")
    void nothingToSealDoesNotUpload() {
        // Dado un buffer que no sella (lote vacío)
        when(buffer.sealCurrentBatch(Source.DISCORD)).thenReturn(Optional.empty());

        // Cuando se dispara por tamaño
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces sin paquete
        verifyNoInteractions(store);
    }

    // ---------- per-source lock ----------

    @Test
    @Timeout(5)
    @DisplayName("Dado un flush de DISCORD en curso, cuando llega otro de DISCORD, entonces se salta sin sellar dos veces")
    void concurrentFlushOfSameSourceIsSkipped() throws Exception {
        // Dado un upload de DISCORD bloqueado dentro del store
        CountDownLatch insideStore = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenAnswer(inv -> {
            insideStore.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new StoreResult(true, false);
        });
        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> service.flushIfFull(Source.DISCORD, MAX_BYTES));
        assertThat(insideStore.await(3, TimeUnit.SECONDS)).isTrue();

        // Cuando llega un segundo flush de la misma fuente mientras el primero sigue
        service.flushIfFull(Source.DISCORD, MAX_BYTES);
        release.countDown();
        first.get(3, TimeUnit.SECONDS);

        // Entonces solo el primero selló (tryLock: el segundo no espera, se va)
        verify(buffer, times(1)).sealCurrentBatch(Source.DISCORD);
    }

    @Test
    @Timeout(5)
    @DisplayName("Dado un flush de DISCORD en curso, cuando llega uno de TELEGRAM, entonces TELEGRAM no espera")
    void flushOfOtherSourceIsNotBlocked() throws Exception {
        // Dado un upload de DISCORD bloqueado dentro del store
        CountDownLatch insideStore = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sealable(Source.DISCORD, "b-d", post("m-1", Source.DISCORD));
        sealable(Source.TELEGRAM, "b-t", post("m-2", Source.TELEGRAM));
        when(store.save(any())).thenAnswer(inv -> {
            AssetPackage pkg = inv.getArgument(0);
            if (pkg.source() == Source.DISCORD) {
                insideStore.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            return new StoreResult(true, false);
        });
        CompletableFuture<Void> discord = CompletableFuture.runAsync(
                () -> service.flushIfFull(Source.DISCORD, MAX_BYTES));
        assertThat(insideStore.await(3, TimeUnit.SECONDS)).isTrue();

        // Cuando TELEGRAM hace flush mientras DISCORD está bloqueado
        service.flushIfFull(Source.TELEGRAM, MAX_BYTES);

        // Entonces TELEGRAM terminó sin esperar a DISCORD
        verify(buffer).discardSealed(Source.TELEGRAM, "b-t");
        verify(buffer, never()).discardSealed(Source.DISCORD, "b-d");
        release.countDown();
        discord.get(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Dado un flush que falló con excepción, cuando llega el siguiente, entonces el lock fue liberado")
    void lockReleasedAfterFailure() {
        // Dado un primer flush que revienta al leer pendientes
        when(buffer.pendingBatches(Source.DISCORD))
                .thenThrow(new IllegalStateException("redis broken"))
                .thenReturn(Set.of());
        sealable(Source.DISCORD, "b-1", post("m-1", Source.DISCORD));
        when(store.save(any())).thenReturn(new StoreResult(true, false));
        try {
            service.flushIfFull(Source.DISCORD, MAX_BYTES);
        } catch (IllegalStateException expected) {
            // the failure itself is not under test here, only the lock release
        }

        // Cuando llega el siguiente flush
        service.flushIfFull(Source.DISCORD, MAX_BYTES);

        // Entonces pudo tomar el lock y sellar (finally liberó el lock)
        verify(buffer).sealCurrentBatch(Source.DISCORD);
    }

    // ---------- fixtures ----------

    private void sealable(Source source, String batchId, EnrichedComment... records) {
        when(buffer.sealCurrentBatch(source)).thenReturn(Optional.of(batchId));
        when(buffer.readSealed(source, batchId)).thenReturn(List.of(records));
    }

    private static EnrichedComment post(String messageId, Source source) {
        return new EnrichedComment(
                "ingest-batch", messageId, "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, null, Instant.parse("2026-10-06T10:00:00Z"), source,
                Channels.FAQ, "Primer empleo dev",
                "Consegui mi primer empleo como dev Java gracias a la comunidad",
                List.of("#EmpleoTech"), null);
    }
}
