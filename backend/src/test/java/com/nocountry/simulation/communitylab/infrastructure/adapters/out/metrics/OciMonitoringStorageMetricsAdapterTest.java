package com.nocountry.simulation.communitylab.infrastructure.adapters.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;
import com.nocountry.simulation.communitylab.application.dtos.RequestCounts;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciAuthMode;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;

/**
 * OCI Monitoring adapter: turns 9 MQL queries into the raw bucket metrics.
 * No Spring context; Mockito only at the SDK boundary ({@link Monitoring}).
 *
 * <p>Derivado de: spec 009 RF-01 (uso, objetos, llamadas por tipo), RF-03 (llamadas desde {@code periodStart};
 * sin datos → {@code 0}), RF-05 (fallo de OCI → no disponible), RNF-01 (tamaño publicado cada hora),
 * RNF-02 (timeout 3 s), RNF-05 (logs sin OCIDs) + plan 009 §1.1 ({@code .grouping()}, {@code tier = "NORMAL"},
 * siempre {@code startTime} y {@code endTime}, último punto del tamaño) + TASK-009-00 (hallazgos 3, 4 y 6).
 */
@DisplayName("OciMonitoringStorageMetricsAdapter")
@ExtendWith(OutputCaptureExtension.class)
class OciMonitoringStorageMetricsAdapterTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..SECRET-COMPARTMENT";
    private static final Instant PERIOD_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T21:30:00Z");

    private Monitoring monitoring;
    private OciMonitoringStorageMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        monitoring = mock(Monitoring.class);
        OciProperties properties = new OciProperties(OciAuthMode.INSTANCE_PRINCIPAL, null,
                "MessagesUsers", "sa-saopaulo-1", "test-namespace", "paquetes/", 3, COMPARTMENT);
        adapter = new OciMonitoringStorageMetricsAdapter(monitoring, properties);
    }

    @AfterEach
    void tearDown() {
        adapter.destroy();
    }

    @Test
    @DisplayName("Dado puntos horarios de cada métrica de llamadas, cuando se lee, entonces cada contador es la suma de sus puntos")
    void requestCountersAreTheSumOfTheirHourlyPoints() {
        // Dado varias horas con llamadas y métricas sin ninguna (PostRequests no existe en el bucket)
        answer(Map.of(
                "AllRequests", series(point("2026-10-08T19:30:00Z", 100.0), point("2026-10-14T21:30:00Z", 82.0)),
                "GetRequests", series(point("2026-10-08T19:30:00Z", 60.0), point("2026-10-14T21:30:00Z", 40.0)),
                "PutRequests", series(point("2026-10-14T21:30:00Z", 30.0)),
                "ListRequests", series(point("2026-10-14T21:30:00Z", 2.0)),
                "ClientErrors", series(point("2026-10-14T21:30:00Z", 10.0))));

        // Cuando se lee el mes
        RequestCounts requests = adapter.read(PERIOD_START, NOW).requests();

        // Entonces cada contador suma sus horas y una métrica sin datos vale 0 (RF-03)
        assertThat(requests).isEqualTo(new RequestCounts(182, 100, 30, 2, 0, 0, 10));
    }

    @Test
    @DisplayName("Dado varias horas de tamaño desordenadas, cuando se lee, entonces usa la más reciente y su hora es measuredAt")
    void sizeIsTheNewestPointAndItsTimeIsMeasuredAt() {
        // Dado el tamaño de las últimas horas, con la más reciente menor (lifecycle borró versiones) y no la última
        answer(Map.of(
                "StoredBytes", series(
                        point("2026-10-14T20:30:00Z", 71_413.0),
                        point("2026-10-14T18:30:00Z", 90_000.0),
                        point("2026-10-14T19:30:00Z", 65_190.0)),
                "ObjectCount", series(
                        point("2026-10-14T19:30:00Z", 15.0),
                        point("2026-10-14T20:30:00Z", 11.0))));

        // Cuando se lee
        BucketMetrics metrics = adapter.read(PERIOD_START, NOW);

        // Entonces el estado actual, no el mayor ni la suma, con la hora del dato de StoredBytes
        assertThat(metrics.usedBytes()).isEqualTo(71_413L);
        assertThat(metrics.objectCount()).isEqualTo(11L);
        assertThat(metrics.measuredAt()).isEqualTo(Instant.parse("2026-10-14T20:30:00Z"));
    }

    @Test
    @DisplayName("Dado un bucket sin ninguna serie publicada, cuando se lee, entonces todo vale 0 y measuredAt es nulo")
    void noSeriesGivesZerosAndNoMeasuredAt() {
        // Dado que OCI aún no publicó nada (items vacíos en todas las consultas)
        answer(Map.of());

        // Cuando se lee
        BucketMetrics metrics = adapter.read(PERIOD_START, NOW);

        // Entonces ceros, sin excepción, y el dashboard sabe que no hay hora de medición
        assertThat(metrics.usedBytes()).isZero();
        assertThat(metrics.objectCount()).isZero();
        assertThat(metrics.measuredAt()).isNull();
        assertThat(metrics.requests()).isEqualTo(RequestCounts.zero());
    }

    @Test
    @DisplayName("Dado una lectura, cuando se consulta OCI, entonces lanza 9 consultas al namespace y compartment correctos")
    void sendsNineQueriesToTheRightNamespaceAndCompartment() {
        // Dado OCI sin datos
        answer(Map.of());

        // Cuando se lee
        adapter.read(PERIOD_START, NOW);

        // Entonces una consulta por métrica, nunca más (RNF-03), con el namespace exacto (un typo devuelve vacío)
        List<SummarizeMetricsDataRequest> requests = capturedRequests(9);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.getCompartmentId()).isEqualTo(COMPARTMENT);
            assertThat(request.getSummarizeMetricsDataDetails().getNamespace()).isEqualTo("oci_objectstorage");
        });
        assertThat(requests).extracting(request -> request.getSummarizeMetricsDataDetails().getQuery())
                .containsExactlyInAnyOrder(
                        "AllRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "GetRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "PutRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "ListRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "HeadRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "DeleteRequests[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "ClientErrors[1h]{resourceDisplayName = \"MessagesUsers\"}.grouping().sum()",
                        "StoredBytes[1h]{resourceDisplayName = \"MessagesUsers\", tier = \"NORMAL\"}.grouping().max()",
                        "ObjectCount[1h]{resourceDisplayName = \"MessagesUsers\", tier = \"NORMAL\"}.grouping().max()");
    }

    @Test
    @DisplayName("Dado una lectura, cuando se consulta OCI, entonces las llamadas van desde periodStart y el tamaño desde 3 h antes, siempre hasta now")
    void everyQueryCarriesItsWindowWithAnEndTime() {
        // Dado OCI sin datos
        answer(Map.of());

        // Cuando se lee
        adapter.read(PERIOD_START, NOW);

        // Entonces nunca sin endTime (OCI respondería vacío) y cada métrica con su periodo
        for (SummarizeMetricsDataRequest request : capturedRequests(9)) {
            SummarizeMetricsDataDetails details = request.getSummarizeMetricsDataDetails();
            assertThat(details.getEndTime()).isEqualTo(Date.from(NOW));
            Instant expectedStart = details.getQuery().endsWith(".sum()")
                    ? PERIOD_START
                    : NOW.minus(Duration.ofHours(3));
            assertThat(details.getStartTime()).as(details.getQuery()).isEqualTo(Date.from(expectedStart));
        }
    }

    @Test
    @DisplayName("Dado OCI respondiendo 403, cuando se lee, entonces no disponible con la causa y el log sin el OCID")
    void ociErrorBecomesUnavailableWithoutLeakingTheCompartment(CapturedOutput output) {
        // Dado un error del SDK cuyo mensaje lleva el compartment (como los reales)
        BmcException forbidden = new BmcException(403, "NotAuthorizedOrNotFound",
                "Authorization failed for compartment " + COMPARTMENT, "opc-request-id-123");
        when(monitoring.summarizeMetricsData(any())).thenThrow(forbidden);

        // Cuando se lee / Entonces el servicio podrá servir la foto anterior o 503 (RF-05)
        assertThatThrownBy(() -> adapter.read(PERIOD_START, NOW))
                .isInstanceOf(StorageMetricsUnavailableException.class)
                .hasCause(forbidden);
        assertThat(output.getOut())
                .contains("storage metrics unavailable: status=403 code=NotAuthorizedOrNotFound")
                .doesNotContain("SECRET-COMPARTMENT")
                .doesNotContain("opc-request-id-123");
    }

    @Test
    @DisplayName("Dado un fallo que no es del SDK, cuando se lee, entonces también es no disponible")
    void unexpectedErrorBecomesUnavailable() {
        // Dado una respuesta que revienta al procesarse
        when(monitoring.summarizeMetricsData(any())).thenThrow(new IllegalStateException("broken"));

        // Cuando se lee / Entonces nunca escapa una excepción cruda al controller
        assertThatThrownBy(() -> adapter.read(PERIOD_START, NOW))
                .isInstanceOf(StorageMetricsUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Dado OCI que no responde, cuando se lee, entonces se rinde a los 3 s como no disponible")
    void slowOciTimesOutAfterThreeSeconds(CapturedOutput output) {
        // Dado consultas que se quedan colgadas
        CountDownLatch never = new CountDownLatch(1);
        when(monitoring.summarizeMetricsData(any())).thenAnswer(invocation -> {
            never.await();
            return SummarizeMetricsDataResponse.builder().items(List.of()).build();
        });

        // Cuando se lee
        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.read(PERIOD_START, NOW))
                .isInstanceOf(StorageMetricsUnavailableException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Entonces el dashboard no espera indefinidamente (RNF-02)
        assertThat(elapsed).isBetween(Duration.ofMillis(2_900), Duration.ofSeconds(5));
        assertThat(output.getOut()).contains("storage metrics unavailable: timeout after 3000ms");
    }

    // ---------- fixtures ----------

    // Why match on the query text: the 9 calls run in parallel, so their order is not deterministic.
    private void answer(Map<String, MetricData> seriesByMetric) {
        when(monitoring.summarizeMetricsData(any())).thenAnswer(invocation -> {
            SummarizeMetricsDataRequest request = invocation.getArgument(0);
            String query = request.getSummarizeMetricsDataDetails().getQuery();
            String metric = query.substring(0, query.indexOf('['));
            MetricData series = seriesByMetric.get(metric);
            return SummarizeMetricsDataResponse.builder()
                    .items(series == null ? List.of() : List.of(series))
                    .build();
        });
    }

    private List<SummarizeMetricsDataRequest> capturedRequests(int expected) {
        ArgumentCaptor<SummarizeMetricsDataRequest> captor = ArgumentCaptor.forClass(SummarizeMetricsDataRequest.class);
        verify(monitoring, times(expected)).summarizeMetricsData(captor.capture());
        return captor.getAllValues();
    }

    private static MetricData series(AggregatedDatapoint... points) {
        return MetricData.builder().aggregatedDatapoints(Arrays.asList(points)).build();
    }

    private static AggregatedDatapoint point(String timestamp, double value) {
        return AggregatedDatapoint.builder()
                .timestamp(Date.from(Instant.parse(timestamp)))
                .value(value)
                .build();
    }
}
