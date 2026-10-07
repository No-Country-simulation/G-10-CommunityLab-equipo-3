package com.nocountry.simulation.communitylab.infrastructure.config.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "buffer.flush.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
