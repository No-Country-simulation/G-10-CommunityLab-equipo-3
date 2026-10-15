package com.nocountry.simulation.communitylab.infrastructure.config.storage;

import com.nocountry.simulation.communitylab.application.port.in.GetStorageStatsUseCase;
import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;
import com.nocountry.simulation.communitylab.application.services.storage.GetStorageStatsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class StorageStatsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // Why a @Bean and not @Service: application must not import StorageStatsProperties from infrastructure (P2).
    @Bean
    public GetStorageStatsUseCase getStorageStatsUseCase(StorageMetrics metrics,
                                                         StorageStatsProperties properties,
                                                         Clock clock) {
        return new GetStorageStatsService(metrics, properties.limitBytes(), properties.monthlyRequestsLimit(),
                properties.cacheTtl(), clock);
    }
}
