package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;

import java.time.Instant;

public interface StorageMetrics {
    BucketMetrics read(Instant periodStart, Instant now);
}
