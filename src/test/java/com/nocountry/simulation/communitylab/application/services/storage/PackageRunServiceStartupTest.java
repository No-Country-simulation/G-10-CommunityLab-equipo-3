package com.nocountry.simulation.communitylab.application.services.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nocountry.simulation.communitylab.application.port.in.PackageRunUseCase;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.PackagePublisher;

/**
 * Startup guard: a flush threshold above 900KB must stop the application from booting,
 * so nobody can raise REDIS_BUFFER_MAX_BYTES and silently break the 1MB package budget.
 * Only the property binding and bean creation are under test; collaborators are mocks.
 *
 * <p>Derivado de: spec 006 RNF-01 (objeto {@code <1MB}, buffer cierra en 921600) +
 * constitución R6 + decisión 2026-10-12 (umbral fuera de {@code 1..921600} = no arranca).
 */
@DisplayName("PackageRunService startup")
class PackageRunServiceStartupTest {

    // Why the placeholder auto-config: it is what resolves ${redis.buffer.max.bytes:921600} in the real app.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Collaborators.class, PackageRunService.class);

    @Test
    @DisplayName("Dado REDIS_BUFFER_MAX_BYTES=921601, cuando arranca, entonces el contexto falla nombrando la variable")
    void thresholdAboveCeilingStopsTheBoot() {
        // Dado un umbral 1 byte por encima del tope
        runner.withPropertyValues("redis.buffer.max.bytes=921601").run(context -> {
            // Entonces la app no arranca y la causa raíz dice qué variable corregir
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REDIS_BUFFER_MAX_BYTES");
        });
    }

    @Test
    @DisplayName("Dado REDIS_BUFFER_MAX_BYTES ausente, cuando arranca, entonces usa 921600 y el contexto levanta")
    void missingThresholdBootsWithTheDefault() {
        // Dado un entorno sin la variable (el default de @Value es el propio tope)
        runner.run(context -> {
            // Entonces el caso de uso queda disponible
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PackageRunUseCase.class);
        });
    }

    @Test
    @DisplayName("Dado REDIS_BUFFER_MAX_BYTES menor que el tope, cuando arranca, entonces el contexto levanta")
    void lowerThresholdIsAllowed() {
        // Dado un umbral más bajo (útil para probar lotes pequeños en local)
        runner.withPropertyValues("redis.buffer.max.bytes=1000").run(context -> {
            // Entonces arranca: el tope limita hacia arriba, no hacia abajo
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PackageRunUseCase.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        // Why mocks: the guard runs in the constructor; no port is called while the context starts.
        @Bean
        BufferPort bufferPort() {
            return mock(BufferPort.class);
        }

        @Bean
        ArtifactStore artifactStore() {
            return mock(ArtifactStore.class);
        }

        @Bean
        PackagePublisher packagePublisher() {
            return mock(PackagePublisher.class);
        }
    }
}
