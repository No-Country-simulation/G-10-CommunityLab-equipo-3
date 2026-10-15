package com.nocountry.simulation.communitylab.application.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Request counters read from OCI Monitoring for the current month.
 *
 * <p>Derivado de: spec 009 RF-03 (una métrica sin datos en el periodo vale {@code 0}) + plan 009 §1.1
 * ({@code RequestCounts.zero()}).
 */
@DisplayName("RequestCounts")
class RequestCountsTest {

    @Test
    @DisplayName("Dado un mes sin llamadas, cuando se usa zero, entonces todos los contadores valen 0")
    void zeroHasEveryCounterAtZero() throws Exception {
        // Dado un mes sin llamadas
        RequestCounts zero = RequestCounts.zero();

        // Cuando se leen todos los contadores / Entonces ninguno se queda fuera (un contador nuevo también debe salir a 0)
        for (RecordComponent component : RequestCounts.class.getRecordComponents()) {
            assertThat(component.getAccessor().invoke(zero))
                    .as(component.getName())
                    .isEqualTo(0L);
        }
    }
}
