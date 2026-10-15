package com.nocountry.simulation.communitylab.infrastructure.config.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding of the storage stats limits and cache TTL from the real {@code application.yaml}.
 * Only the properties bean is started: no OCI client, no web layer.
 *
 * <p>Derivado de: spec 009 RF-02 (límites por configuración: 10 GB Standard y 50 000 llamadas/mes por defecto),
 * RF-04 (TTL de la caché, 60 s por defecto) + plan 009 §1.1 (límite {@code <= 0} → falla al arrancar),
 * §2 (variables {@code STORAGE_LIMIT_BYTES}, {@code STORAGE_REQUESTS_MONTHLY_LIMIT}, {@code STORAGE_STATS_CACHE_TTL}).
 */
@DisplayName("StorageStatsProperties")
class StorageStatsPropertiesTest {

    // Why the initializer: it loads src/main/resources/application.yaml, so the defaults under test are the shipped ones.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("Dado ninguna variable STORAGE_*, cuando arranca, entonces usa 10 GB, 50 000 llamadas/mes y 60 s")
    void defaultsAreTheFreeTierMargins() {
        // Dado un entorno sin variables (como los tests y el compose actual)
        // Cuando arranca
        runner.run(context -> {
            // Entonces el frontend recibe los márgenes gratuitos sin configurar nada
            assertThat(context).hasNotFailed();
            StorageStatsProperties properties = context.getBean(StorageStatsProperties.class);
            assertThat(properties.limitBytes()).isEqualTo(10_737_418_240L);
            assertThat(properties.monthlyRequestsLimit()).isEqualTo(50_000L);
            assertThat(properties.cacheTtl()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    @DisplayName("Dado las variables de entorno de la spec, cuando arranca, entonces sustituyen a los valores por defecto")
    void environmentVariablesOverrideTheDefaults() {
        // Dado una cuenta solo Always Free (20 GB compartidos) y otro TTL, con los nombres exactos de la spec
        runner.withPropertyValues(
                        "STORAGE_LIMIT_BYTES=21474836480",
                        "STORAGE_REQUESTS_MONTHLY_LIMIT=100000",
                        "STORAGE_STATS_CACHE_TTL=PT2M")
                // Cuando arranca
                .run(context -> {
                    // Entonces se usan esos valores; un nombre mal escrito dejaría el default en silencio
                    StorageStatsProperties properties = context.getBean(StorageStatsProperties.class);
                    assertThat(properties.limitBytes()).isEqualTo(21_474_836_480L);
                    assertThat(properties.monthlyRequestsLimit()).isEqualTo(100_000L);
                    assertThat(properties.cacheTtl()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    @Test
    @DisplayName("Dado un TTL con unidad corta (5m), cuando arranca, entonces se interpreta como duración")
    void cacheTtlAcceptsSimpleUnits() {
        // Dado el formato corto que usa el default (60s)
        runner.withPropertyValues("STORAGE_STATS_CACHE_TTL=5m")
                // Cuando arranca / Entonces el TTL lleva su unidad, no es un número suelto
                .run(context -> assertThat(context.getBean(StorageStatsProperties.class).cacheTtl())
                        .isEqualTo(Duration.ofMinutes(5)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "STORAGE_LIMIT_BYTES, 0, limitBytes",
            "STORAGE_LIMIT_BYTES, -1, limitBytes",
            "STORAGE_REQUESTS_MONTHLY_LIMIT, 0, monthlyRequestsLimit",
            "STORAGE_REQUESTS_MONTHLY_LIMIT, -50000, monthlyRequestsLimit"
    })
    @DisplayName("Dado un límite cero o negativo, cuando arranca, entonces no arranca y nombra la propiedad")
    void nonPositiveLimitFailsAtStartup(String variable, String value, String property) {
        // Dado un límite sin sentido como denominador de "x / límite"
        runner.withPropertyValues(variable + "=" + value)
                // Cuando arranca
                .run(context -> {
                    // Entonces falla al arrancar en vez de enviar datos absurdos al frontend
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(property);
                });
    }

    @Test
    @DisplayName("Dado un límite que no es un número (10GB), cuando arranca, entonces no arranca")
    void nonNumericLimitFailsAtStartup() {
        // Dado un valor con unidad escrito a mano
        runner.withPropertyValues("STORAGE_LIMIT_BYTES=10GB")
                // Cuando arranca / Entonces falla al convertir, no se queda con un límite inventado
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageStatsProperties.class)
    static class PropertiesOnly {
    }
}
