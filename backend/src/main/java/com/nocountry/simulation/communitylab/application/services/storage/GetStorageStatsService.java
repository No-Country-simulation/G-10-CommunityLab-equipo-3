package com.nocountry.simulation.communitylab.application.services.storage;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;
import com.nocountry.simulation.communitylab.application.dtos.RequestCounts;
import com.nocountry.simulation.communitylab.application.dtos.StorageStats;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.application.port.in.GetStorageStatsUseCase;
import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class GetStorageStatsService implements GetStorageStatsUseCase {

    // Why a constant: the query only reads OCI's Standard tier, and the limit sent with it is the Standard one.
    private static final String STANDARD_TIER = "STANDARD";

    private final StorageMetrics metrics;
    private final long limitBytes;
    private final long monthlyRequestsLimit;
    private final Duration cacheTtl;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    // Why volatile: the fast path reads it without the lock and must see the latest snapshot.
    private volatile StorageStats cached;

    public GetStorageStatsService(StorageMetrics metrics, long limitBytes, long monthlyRequestsLimit, Duration cacheTtl, Clock clock) {
        this.metrics = metrics;
        this.limitBytes = limitBytes;
        this.monthlyRequestsLimit = monthlyRequestsLimit;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    @Override
    public StorageStats get() {
        StorageStats snapshot = cached;
        if (isFresh(snapshot, Instant.now(clock))) {
            return snapshot;
        }

        lock.lock();
        try {
            // Why check again: requests queued on the lock must reuse the snapshot the first one just built
            Instant now = Instant.now(clock);
            if (isFresh(cached, now)) {
                return cached;
            }
            return refresh(now);
        } finally {
            lock.unlock();
        }
    }

    private StorageStats refresh(Instant now) {
        Instant periodStart = monthStart(now);
        try {
            cached = toStats(metrics.read(periodStart, now), periodStart, now);
            return cached;
        } catch (StorageMetricsUnavailableException e) {
            if (cached == null) {
                throw e;
            }
            log.warn("storage metrics unavailable, serving stale snapshot: generatedAt={}", cached.generatedAt());
            return cached.asStale();
        }
    }

    private boolean isFresh(StorageStats snapshot, Instant now) {
        return snapshot != null && snapshot.generatedAt().plus(cacheTtl).isAfter(now);
    }

    // Why UTC: OCI's free tier month starts at 00:00Z, not in the VM's or the developer's time zone.
    private static Instant monthStart(Instant now) {
        return now.atZone(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant();
    }

    private StorageStats toStats(BucketMetrics bucket, Instant periodStart, Instant now) {
        RequestCounts requests = bucket.requests();
        return new StorageStats(
                new StorageStats.Storage(STANDARD_TIER, bucket.usedBytes(), limitBytes,
                        bucket.objectCount(), bucket.measuredAt()),
                new StorageStats.Requests(periodStart, monthlyRequestsLimit, requests.total(), requests.get(),
                        requests.put(), requests.list(), requests.head(), requests.delete(), requests.clientErrors()),
                now,
                false);
    }
}
