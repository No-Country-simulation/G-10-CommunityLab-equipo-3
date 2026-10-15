package com.nocountry.simulation.communitylab.infrastructure.adapters.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.monitoring.Monitoring;

/**
 * Wiring tests: for every OCI_AUTH_MODE exactly one StorageMetrics exists, so the stats service
 * never gets zero or two candidates.
 *
 * <p>Derivado de: spec 009 RF-05 (none → 503 sin OCI; session-token/instance-principal → Monitoring) + plan 009 §1.1
 * (condiciones complementarias de los dos adapters).
 */
@DisplayName("StorageMetrics selection")
class StorageMetricsSelectionTest {

    // Why the placeholder auto-config: it is what resolves ${...} inside @ConditionalOnExpression in the real app.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Collaborators.class,
                    StorageMetricsNotConfiguredAdapter.class, OciMonitoringStorageMetricsAdapter.class);

    @Test
    @DisplayName("Dado oci.auth-mode ausente, cuando arranca, entonces solo existe el adapter degradado")
    void onlyDegradedAdapterWhenModeAbsent() {
        // Dado un contexto sin oci.auth-mode (las dos condiciones deben coincidir en el default none)
        runner.run(context -> {
            // Entonces exactamente 1 StorageMetrics y es el degradado
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StorageMetrics.class);
            assertThat(context.getBean(StorageMetrics.class)).isInstanceOf(StorageMetricsNotConfiguredAdapter.class);
        });
    }

    @Test
    @DisplayName("Dado oci.auth-mode=none, cuando arranca, entonces solo existe el adapter degradado")
    void onlyDegradedAdapterWhenModeIsNone() {
        // Dado OCI apagado explicitamente
        runner.withPropertyValues("oci.auth-mode=none").run(context -> {
            // Entonces exactamente 1 StorageMetrics y es el degradado
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StorageMetrics.class);
            assertThat(context.getBean(StorageMetrics.class)).isInstanceOf(StorageMetricsNotConfiguredAdapter.class);
        });
    }

    @ParameterizedTest(name = "oci.auth-mode={0}")
    @ValueSource(strings = {"session-token", "instance-principal"})
    @DisplayName("Dado OCI activo, cuando arranca, entonces solo existe el adapter de Monitoring")
    void onlyMonitoringAdapterWhenOciIsActive(String mode) {
        // Dado OCI activo en local o en la VM
        runner.withPropertyValues("oci.auth-mode=" + mode).run(context -> {
            // Entonces exactamente 1 StorageMetrics y es el real (sin NoUniqueBeanDefinitionException)
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StorageMetrics.class);
            assertThat(context.getBean(StorageMetrics.class)).isInstanceOf(OciMonitoringStorageMetricsAdapter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OciProperties.class)
    static class Collaborators {

        // Why a mock client: this test checks which adapter is wired, not OCI auth (covered by OciConfigTest).
        @Bean
        Monitoring monitoring() {
            return mock(Monitoring.class);
        }
    }
}
