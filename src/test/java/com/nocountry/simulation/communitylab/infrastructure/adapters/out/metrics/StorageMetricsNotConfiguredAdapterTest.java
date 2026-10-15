package com.nocountry.simulation.communitylab.infrastructure.adapters.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;

/**
 * Degraded metrics used when {@code OCI_AUTH_MODE=none}: the endpoint must say "no storage", never "empty bucket".
 *
 * <p>Derivado de: spec 009 RF-05 ({@code OCI_AUTH_MODE=none} → {@code 503} sin llamar a OCI) + plan 009 §1.1
 * ({@code StorageMetricsNotConfiguredAdapter}, como {@code ArtifactStoreAdapter}).
 */
@DisplayName("StorageMetricsNotConfiguredAdapter (OCI_AUTH_MODE=none)")
@ExtendWith(OutputCaptureExtension.class)
class StorageMetricsNotConfiguredAdapterTest {

    private final StorageMetricsNotConfiguredAdapter adapter = new StorageMetricsNotConfiguredAdapter();

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se leen las métricas, entonces no configurado y no ceros")
    void readIsNotConfigured(CapturedOutput output) {
        // Dado / Cuando se piden las métricas
        // Entonces 503 OCI_NOT_CONFIGURED en el handler, en vez de un bucket vacío que no existe
        assertThatThrownBy(() -> adapter.read(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-14T21:30:00Z")))
                .isInstanceOf(StorageNotConfiguredException.class)
                .hasMessage("OCI_NOT_CONFIGURED");
        assertThat(output.getOut()).contains("OCI_NOT_CONFIGURED: storage metrics");
    }
}
