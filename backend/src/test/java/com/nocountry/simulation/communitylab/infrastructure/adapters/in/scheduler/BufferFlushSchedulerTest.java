package com.nocountry.simulation.communitylab.infrastructure.adapters.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.nocountry.simulation.communitylab.application.port.in.PackageRunUseCase;

/**
 * Hourly inbound adapter: it only delegates; the {@code count >= min-messages}
 * rule lives in the use case.
 *
 * <p>Derivado de: regla nueva flush horario por fuente (spec 006) + plan 006 §1
 * (scheduler como adapter de entrada, sin lógica de negocio).
 */
@DisplayName("BufferFlushScheduler")
class BufferFlushSchedulerTest {

    @Test
    @DisplayName("Dado un tick del reloj, cuando corre, entonces delega una sola vez en el caso de uso")
    void tickDelegatesToUseCase() {
        // Dado el scheduler con el caso de uso
        PackageRunUseCase useCase = mock(PackageRunUseCase.class);
        BufferFlushScheduler scheduler = new BufferFlushScheduler(useCase);

        // Cuando corre el tick
        scheduler.flushHourly();

        // Entonces solo el flush programado, nunca el de tamaño
        verify(useCase).flushScheduled();
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("Dado el cron sin configurar, cuando se lee su default, entonces es cada hora en punto")
    void defaultCronIsHourly() throws Exception {
        // Dado el método programado
        Method tick = BufferFlushScheduler.class.getMethod("flushHourly");

        // Cuando se lee la anotación / Entonces el fallback es horario (no diario)
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${buffer.flush.cron:0 0 * * * *}");
    }
}
