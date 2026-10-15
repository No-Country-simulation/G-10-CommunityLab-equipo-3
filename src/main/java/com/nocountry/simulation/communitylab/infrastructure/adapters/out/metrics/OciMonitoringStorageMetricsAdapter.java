package com.nocountry.simulation.communitylab.infrastructure.adapters.out.metrics;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;
import com.nocountry.simulation.communitylab.application.dtos.RequestCounts;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class OciMonitoringStorageMetricsAdapter implements StorageMetrics, DisposableBean {

    private static final String NAMESPACE = "oci_objectstorage";
    // Why NORMAL: OCI Monitoring names the Standard tier NORMAL in the tier dimension.
    private static final String STANDARD_TIER = "NORMAL";
    // Why 3h: size and count are published hourly, so 3h survives a late publication.
    private static final Duration SIZE_LOOKBACK = Duration.ofHours(3);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);
    private static final int QUERIES = 9;

    private final Monitoring monitoring;
    private final OciProperties ociProperties;
    // Why platform threads and not virtual: the SDK's blocking HTTP client pins virtual threads on JDK 21.
    private final ExecutorService executor = Executors.newFixedThreadPool(QUERIES);

    @Override
    public BucketMetrics read(Instant periodStart, Instant now) {
        Instant sizeStart = now.minus(SIZE_LOOKBACK);

        // Why in parallel: 9 sequential round trips to OCI would blow the 3s budget (RNF-02).
        CompletableFuture<Long> total = async(() -> total("AllRequests", periodStart, now));
        CompletableFuture<Long> get = async(() -> total("GetRequests", periodStart, now));
        CompletableFuture<Long> put = async(() -> total("PutRequests", periodStart, now));
        CompletableFuture<Long> list = async(() -> total("ListRequests", periodStart, now));
        CompletableFuture<Long> head = async(() -> total("HeadRequests", periodStart, now));
        CompletableFuture<Long> delete = async(() -> total("DeleteRequests", periodStart, now));
        CompletableFuture<Long> clientErrors = async(() -> total("ClientErrors", periodStart, now));
        CompletableFuture<Optional<AggregatedDatapoint>> storedBytes = async(() -> latest("StoredBytes", sizeStart, now));
        CompletableFuture<Optional<AggregatedDatapoint>> objectCount = async(() -> latest("ObjectCount", sizeStart, now));

        awaitAll(total, get, put, list, head, delete, clientErrors, storedBytes, objectCount);

        Optional<AggregatedDatapoint> size = storedBytes.join();
        return new BucketMetrics(
                size.map(point -> point.getValue().longValue()).orElse(0L),
                objectCount.join().map(point -> point.getValue().longValue()).orElse(0L),
                // Why the StoredBytes time: measuredAt tells how old the used bytes are (spec RF-01.5).
                size.map(point -> point.getTimestamp().toInstant()).orElse(null),
                new RequestCounts(total.join(), get.join(), put.join(), list.join(),
                        head.join(), delete.join(), clientErrors.join()));
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private long total(String metric, Instant start, Instant end) {
        return (long) datapoints(requestsQuery(metric), start, end).stream()
                .mapToDouble(AggregatedDatapoint::getValue)
                .sum();
    }

    // Why the newest and not the biggest: size goes down when lifecycle deletes old versions (spec 007).
    private Optional<AggregatedDatapoint> latest(String metric, Instant start, Instant end) {
        return datapoints(sizeQuery(metric), start, end).stream()
                .max(Comparator.comparing(AggregatedDatapoint::getTimestamp));
    }

    private List<AggregatedDatapoint> datapoints(String query, Instant start, Instant end) {
        // Why both times: with only startTime OCI answers an empty list instead of an error.
        SummarizeMetricsDataDetails details = SummarizeMetricsDataDetails.builder()
                .namespace(NAMESPACE)
                .query(query)
                .startTime(Date.from(start))
                .endTime(Date.from(end))
                .build();

        SummarizeMetricsDataRequest request = SummarizeMetricsDataRequest.builder()
                .compartmentId(ociProperties.compartmentId())
                .summarizeMetricsDataDetails(details)
                .build();

        SummarizeMetricsDataResponse response = monitoring.summarizeMetricsData(request);
        List<MetricData> series = response.getItems();

        // Why flatMap over every series: an empty month has no series at all, so getFirst() would throw.
        return series.stream()
                .flatMap(metric -> metric.getAggregatedDatapoints().stream())
                .toList();
    }

    // Why sum: each request metric counts events, so the hourly totals add up over the month.
    private String requestsQuery(String metric) {
        return "%s[1h]{resourceDisplayName = \"%s\"}.grouping().sum()"
                .formatted(metric, ociProperties.bucket());
    }

    // Why max: size and count are a state, not events; read() keeps the latest hour, never a sum.
    private String sizeQuery(String metric) {
        return "%s[1h]{resourceDisplayName = \"%s\", tier = \"%s\"}.grouping().max()"
                .formatted(metric, ociProperties.bucket(), STANDARD_TIER);
    }

    private <T> CompletableFuture<T> async(Supplier<T> query) {
        return CompletableFuture.supplyAsync(query, executor);
    }

    private void awaitAll(CompletableFuture<?>... queries) {
        try {
            CompletableFuture.allOf(queries).get(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel(queries);
            log.warn("storage metrics unavailable: timeout after {}ms", QUERY_TIMEOUT.toMillis());
            throw new StorageMetricsUnavailableException("OCI Monitoring timed out", e);
        } catch (ExecutionException e) {
            cancel(queries);
            throw unavailable(e.getCause());
        } catch (InterruptedException e) {
            cancel(queries);
            // Why restore the flag: the caller's thread must still see it was asked to stop.
            Thread.currentThread().interrupt();
            throw new StorageMetricsUnavailableException("interrupted while reading OCI Monitoring", e);
        }
    }

    // Why only status and service code: the SDK message carries the compartment OCID and request ids (RNF-05).
    private StorageMetricsUnavailableException unavailable(Throwable cause) {
        if (cause instanceof BmcException oci) {
            log.warn("storage metrics unavailable: status={} code={}", oci.getStatusCode(), oci.getServiceCode());
        } else {
            log.warn("storage metrics unavailable: error={}", cause.getClass().getSimpleName());
        }
        return new StorageMetricsUnavailableException("OCI Monitoring unavailable", cause);
    }

    private static void cancel(CompletableFuture<?>... queries) {
        for (CompletableFuture<?> query : queries) {
            query.cancel(true);
        }
    }
}
