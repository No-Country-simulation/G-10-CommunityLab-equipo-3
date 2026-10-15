package com.nocountry.simulation.communitylab.application.dtos;

import java.time.Instant;

public record BucketMetrics(
        long usedBytes,
        long objectCount,
        Instant measuredAt,
        RequestCounts requests
) {
}
