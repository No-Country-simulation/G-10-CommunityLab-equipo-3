package com.nocountry.simulation.communitylab.application.dtos;

import java.time.Instant;

public record StorageStats(
        Storage storage,
        Requests requests,
        Instant generatedAt,
        boolean stale
) {
    // Why a copy and not a setter: the cached snapshot is shared by every request, so it must never change in place.
    public StorageStats asStale() {
        return new StorageStats(storage, requests, generatedAt, true);
    }

    public record Storage(String tier, long usedBytes, long limitBytes, long objectCount, Instant measuredAt){ }
    public record Requests(Instant periodStart, long limit, long total, long get, long put, long list, long head, long delete, long clientErrors){}
}
