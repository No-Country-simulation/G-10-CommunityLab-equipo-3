package com.nocountry.simulation.communitylab.infrastructure.config.storage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "storage.stats")
@Validated
public record StorageStatsProperties (
        @Positive
        long limitBytes,
        @Positive
        long monthlyRequestsLimit,
        @NotNull
        Duration cacheTtl
){


}
