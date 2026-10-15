package com.nocountry.simulation.communitylab.application.services.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;
import com.nocountry.simulation.communitylab.application.dtos.RequestCounts;
import com.nocountry.simulation.communitylab.application.dtos.StorageStats;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;

/**
 * Storage stats use case: one OCI read per TTL for every client, the free-tier limits from configuration,
 * the current UTC month for requests and the previous snapshot as stale when OCI fails.
 * No Spring context; Mockito only at the {@link StorageMetrics} boundary; the time is a controlled {@link Clock}.
 *
 * <p>Derivado de: spec 009 RF-01 (foto con tier, uso, límites, llamadas, {@code generatedAt}, {@code stale}),
 * RF-02 (límites de configuración, sin recortar), RF-03 (mes natural UTC), RF-04 (caché compartida),
 * RF-05 (stale con foto previa; sin foto → no disponible; none → no configurado) + plan 009 §1.1
 * (doble comprobación bajo lock).
 */
@DisplayName("GetStorageStatsService")
@ExtendWith(OutputCaptureExtension.class)
class GetStorageStatsServiceTest {

    private static final long LIMIT_BYTES = 10_737_418_240L;
    private static final long MONTHLY_REQUESTS_LIMIT = 50_000L;
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-10-14T21:30:00Z");

    private StorageMetrics metrics;
    private MutableClock clock;
    private GetStorageStatsService service;

    @BeforeEach
    void setUp() {
        metrics = mock(StorageMetrics.class);
        clock = new MutableClock(NOW);
        service = new GetStorageStatsService(metrics, LIMIT_BYTES, MONTHLY_REQUESTS_LIMIT, TTL, clock);
    }

    @Test
    @DisplayName("Dado métricas del bucket, cuando se pide la foto, entonces lleva STANDARD, los datos de OCI, los límites y la hora")
    void snapshotCarriesTheBucketDataAndTheConfiguredLimits() {
        // Dado lo que leyó el adapter
        Instant measuredAt = Instant.parse("2026-10-14T20:30:00Z");
        when(metrics.read(any(), any())).thenReturn(new BucketMetrics(71_413L, 11L, measuredAt,
                new RequestCounts(182, 120, 30, 2, 0, 0, 30)));

        // Cuando se pide
        StorageStats stats = service.get();

        // Entonces el frontend recibe "usado / límite" de las dos cosas, sin campos derivados
        assertThat(stats.storage()).isEqualTo(new StorageStats.Storage("STANDARD", 71_413L, LIMIT_BYTES, 11L, measuredAt));
        assertThat(stats.requests()).isEqualTo(new StorageStats.Requests(Instant.parse("2026-10-01T00:00:00Z"),
                MONTHLY_REQUESTS_LIMIT, 182, 120, 30, 2, 0, 0, 30));
        assertThat(stats.generatedAt()).isEqualTo(NOW);
        assertThat(stats.stale()).isFalse();
    }

    @Test
    @DisplayName("Dado un uso por encima del límite, cuando se pide la foto, entonces se envía tal cual, sin recortar")
    void usageAboveTheLimitIsSentAsIs() {
        // Dado 12 GB usados y 60 000 llamadas
        when(metrics.read(any(), any())).thenReturn(new BucketMetrics(12_884_901_888L, 1L, NOW,
                new RequestCounts(60_000, 0, 0, 0, 0, 0, 0)));

        // Cuando se pide
        StorageStats stats = service.get();

        // Entonces el frontend ve que se pasó (RF-02)
        assertThat(stats.storage().usedBytes()).isGreaterThan(stats.storage().limitBytes());
        assertThat(stats.requests().total()).isGreaterThan(stats.requests().limit());
    }

    @ParameterizedTest(name = "now={0} -> periodStart={1}")
    @CsvSource({
            "2026-10-14T21:30:00Z, 2026-10-01T00:00:00Z",
            "2026-11-01T00:30:00Z, 2026-11-01T00:00:00Z",
            "2026-10-31T23:59:59Z, 2026-10-01T00:00:00Z",
            "2026-12-31T23:59:00Z, 2026-12-01T00:00:00Z",
            "2027-01-01T00:00:00Z, 2027-01-01T00:00:00Z"
    })
    @DisplayName("Dado una fecha, cuando se pide la foto, entonces las llamadas se piden desde el día 1 del mes UTC")
    void requestsAreReadFromTheFirstDayOfTheUtcMonth(String now, String expectedStart) {
        // Dado el instante de la petición
        clock.set(Instant.parse(now));
        when(metrics.read(any(), any())).thenReturn(emptyBucket());

        // Cuando se pide
        StorageStats stats = service.get();

        // Entonces el periodo es el mes natural de OCI, hasta ahora (RF-03)
        verify(metrics).read(Instant.parse(expectedStart), Instant.parse(now));
        assertThat(stats.requests().periodStart()).isEqualTo(Instant.parse(expectedStart));
    }

    @Test
    @DisplayName("Dado una VM en otra zona horaria, cuando es día 1 en UTC pero 31 en local, entonces el periodo es el del día 1 UTC")
    void periodIgnoresTheMachineTimeZone() {
        // Dado las 20:00 del 31 de octubre en Bogotá (-05:00) = 1 de noviembre 01:00Z
        Instant now = Instant.parse("2026-11-01T01:00:00Z");
        service = new GetStorageStatsService(metrics, LIMIT_BYTES, MONTHLY_REQUESTS_LIMIT, TTL,
                Clock.fixed(now, ZoneId.of("America/Bogota")));
        when(metrics.read(any(), any())).thenReturn(emptyBucket());

        // Cuando se pide / Entonces el contador ya es el de noviembre, como en OCI
        assertThat(service.get().requests().periodStart()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("Dado una foto de hace 10 s, cuando se vuelve a pedir, entonces no se consulta OCI y se recibe la misma")
    void freshSnapshotIsServedWithoutCallingOci() {
        // Dado una foto recién obtenida
        when(metrics.read(any(), any())).thenReturn(emptyBucket());
        StorageStats first = service.get();

        // Cuando 10 s después se vuelve a pedir
        clock.advance(Duration.ofSeconds(10));
        StorageStats second = service.get();

        // Entonces misma foto y una sola consulta (RF-04)
        assertThat(second).isSameAs(first);
        verify(metrics, times(1)).read(any(), any());
    }

    @Test
    @DisplayName("Dado una foto de hace 60 s o más, cuando se pide, entonces se consulta OCI y generatedAt cambia")
    void expiredSnapshotIsRefreshed() {
        // Dado una foto obtenida a las 21:30:00
        when(metrics.read(any(), any())).thenReturn(emptyBucket());
        service.get();

        // Cuando justo vence el TTL
        clock.advance(TTL);
        StorageStats refreshed = service.get();

        // Entonces nueva consulta y nueva hora
        verify(metrics, times(2)).read(any(), any());
        assertThat(refreshed.generatedAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    @DisplayName("Dado 10 peticiones simultáneas sin foto, cuando se piden, entonces OCI recibe una sola consulta y todas la misma foto")
    void concurrentRequestsShareASingleOciRead() throws Exception {
        // Dado una consulta lenta a OCI, para que las 10 peticiones lleguen mientras está en curso
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(metrics.read(any(), any())).thenAnswer(invocation -> {
            readStarted.countDown();
            release.await();
            return emptyBucket();
        });

        // Cuando 10 dashboards la piden a la vez
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<StorageStats>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(service::get));
            }
            readStarted.await();
            Thread.sleep(100);
            release.countDown();

            // Entonces una sola consulta y la misma foto para todos (doble comprobación bajo lock)
            StorageStats first = results.getFirst().get();
            for (Future<StorageStats> result : results) {
                assertThat(result.get()).isSameAs(first);
            }
            verify(metrics, times(1)).read(any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Dado una foto previa y OCI caído, cuando se pide, entonces la misma foto marcada stale y con su generatedAt")
    void previousSnapshotIsServedAsStaleWhenOciFails(CapturedOutput output) {
        // Dado una foto de las 21:30:00 y, un minuto después, OCI sin responder
        when(metrics.read(any(), any()))
                .thenReturn(emptyBucket())
                .thenThrow(new StorageMetricsUnavailableException("OCI Monitoring unavailable"));
        StorageStats previous = service.get();
        clock.advance(TTL);

        // Cuando se pide
        StorageStats stale = service.get();

        // Entonces 200 con los datos anteriores y la hora real de esos datos (RF-05)
        assertThat(stale.stale()).isTrue();
        assertThat(stale.generatedAt()).isEqualTo(previous.generatedAt());
        assertThat(stale.storage()).isEqualTo(previous.storage());
        assertThat(output.getOut()).contains("serving stale snapshot: generatedAt=" + NOW);
    }

    @Test
    @DisplayName("Dado un stale servido y OCI de vuelta, cuando se pide, entonces foto nueva sin stale")
    void recoveryAfterStaleGivesAFreshSnapshot() {
        // Dado foto, caída de OCI y vuelta de OCI
        when(metrics.read(any(), any()))
                .thenReturn(emptyBucket())
                .thenThrow(new StorageMetricsUnavailableException("down"))
                .thenReturn(emptyBucket());
        service.get();
        clock.advance(TTL);
        service.get();

        // Cuando OCI responde de nuevo
        StorageStats recovered = service.get();

        // Entonces la caché no quedó marcada como stale
        assertThat(recovered.stale()).isFalse();
        assertThat(recovered.generatedAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    @DisplayName("Dado un stale servido, cuando se pide de nuevo enseguida, entonces se reintenta OCI (el stale no se cachea como fresco)")
    void staleIsNotCachedAsFresh() {
        // Dado una caída de OCI con foto previa
        when(metrics.read(any(), any()))
                .thenReturn(emptyBucket())
                .thenThrow(new StorageMetricsUnavailableException("down"));
        service.get();
        clock.advance(TTL);
        service.get();

        // Cuando se pide otra vez sin avanzar el reloj
        service.get();

        // Entonces se vuelve a intentar: la foto vieja sigue caducada
        verify(metrics, times(3)).read(any(), any());
    }

    @Test
    @DisplayName("Dado ninguna foto previa y OCI caído, cuando se pide, entonces no disponible (503)")
    void noPreviousSnapshotPropagatesUnavailable() {
        // Dado el primer intento tras arrancar, con OCI caído
        StorageMetricsUnavailableException down = new StorageMetricsUnavailableException("down");
        when(metrics.read(any(), any())).thenThrow(down);

        // Cuando se pide / Entonces no hay nada que servir
        assertThatThrownBy(() -> service.get()).isSameAs(down);
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se pide, entonces no configurado (503) y nunca una foto")
    void notConfiguredIsNeverHidden() {
        // Dado el adapter degradado de auth-mode=none
        when(metrics.read(any(), any())).thenThrow(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"));

        // Cuando se pide / Entonces sube tal cual hasta el handler
        assertThatThrownBy(() -> service.get()).isInstanceOf(StorageNotConfiguredException.class);
    }

    @Test
    @DisplayName("Dado una foto previa y OCI sin configurar, cuando se pide, entonces no configurado y no stale")
    void notConfiguredIsNotServedAsStale() {
        // Dado una foto previa y después el storage desconfigurado
        when(metrics.read(any(), any()))
                .thenReturn(emptyBucket())
                .thenThrow(new StorageNotConfiguredException("OCI_NOT_CONFIGURED"));
        service.get();
        clock.advance(TTL);

        // Cuando se pide / Entonces solo un fallo de OCI justifica servir la foto anterior
        assertThatThrownBy(() -> service.get()).isInstanceOf(StorageNotConfiguredException.class);
    }

    // ---------- fixtures ----------

    private static BucketMetrics emptyBucket() {
        return new BucketMetrics(0L, 0L, null, RequestCounts.zero());
    }

    // Why a mutable clock: the TTL is tested by moving time, never by sleeping.
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
