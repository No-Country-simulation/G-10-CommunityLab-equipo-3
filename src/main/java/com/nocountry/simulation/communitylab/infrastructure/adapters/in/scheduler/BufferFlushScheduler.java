package com.nocountry.simulation.communitylab.infrastructure.adapters.in.scheduler;

import com.nocountry.simulation.communitylab.application.port.in.PackageRunUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "buffer.flush.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BufferFlushScheduler {
    private final PackageRunUseCase packageRunUseCase;

    @Scheduled(cron = "${buffer.flush.cron:0 0 * * * *}")
    public void flushHourly(){
        packageRunUseCase.flushScheduled();
    }
}
