package com.nocountry.simulation.communitylab.infrastructure.config.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import com.nocountry.simulation.communitylab.application.port.in.PackageRunUseCase;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.scheduler.BufferFlushScheduler;
import com.nocountry.simulation.communitylab.infrastructure.adapters.out.event.SseEventPublisherAdapter;

/**
 * The scheduling switch is global; only the hourly flush is optional.
 *
 * <p>Derivado de: plan 006 §1.1 + §3 ({@code @EnableScheduling} sin condicional: el heartbeat SSE
 * debe latir aunque {@code buffer.flush.scheduler.enabled=false}; solo {@code BufferFlushScheduler}
 * conserva el suyo). Minimal context: the real config, the real adapter and the real flush scheduler.
 */
@DisplayName("SchedulingConfig")
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, SseEventPublisherAdapter.class, BufferFlushScheduler.class)
            .withBean(PackageRunUseCase.class, () -> mock(PackageRunUseCase.class));

    @Test
    @DisplayName("Dado el flush horario apagado, cuando arranca el contexto, entonces el heartbeat SSE sigue programado y el flush no")
    void heartbeatStaysScheduledWhenTheFlushIsDisabled() {
        runner.withPropertyValues("buffer.flush.scheduler.enabled=false")
                .run(context -> {
                    // Entonces pausar el flush no apaga el latido (volverían los 524 de Cloudflare)
                    assertThat(context).doesNotHaveBean(BufferFlushScheduler.class);
                    assertThat(scheduledHeartbeat(context.getBean(ScheduledTaskHolder.class)))
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("Dado el flush horario encendido, cuando arranca el contexto, entonces conviven el flush y el heartbeat")
    void flushAndHeartbeatAreBothScheduledByDefault() {
        runner.run(context -> {
            // Entonces matchIfMissing=true sigue encendiendo el flush
            assertThat(context).hasSingleBean(BufferFlushScheduler.class);
            assertThat(scheduledHeartbeat(context.getBean(ScheduledTaskHolder.class)))
                    .isEqualTo(Duration.ofSeconds(30));
        });
    }

    // The registered task is what actually runs; its interval is the one Cloudflare sees.
    private static Duration scheduledHeartbeat(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(FixedRateTask.class::isInstance)
                .map(FixedRateTask.class::cast)
                .filter(task -> task.getRunnable().toString().endsWith("SseEventPublisherAdapter.heartbeat"))
                .map(FixedRateTask::getIntervalDuration)
                .findFirst()
                .orElseThrow(() -> new AssertionError("heartbeat is not scheduled: " + holder.getScheduledTasks()));
    }
}
