package com.nocountry.simulation.communitylab.application.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot sent to the dashboard: marking it stale must keep the data and never touch the cached copy.
 *
 * <p>Derivado de: spec 009 RF-05 (OCI falla con foto previa → misma foto con {@code stale=true}) + plan 009 §1.1
 * (foto en memoria compartida por todas las peticiones).
 */
@DisplayName("StorageStats")
class StorageStatsTest {

    @Test
    @DisplayName("Dado una foto vigente, cuando se marca como stale, entonces conserva los datos y su generatedAt")
    void staleCopyKeepsTheDataAndItsTime() {
        // Dado la foto obtenida a las 15:42
        StorageStats fresh = snapshot();

        // Cuando OCI falla y se sirve marcada como stale
        StorageStats stale = fresh.asStale();

        // Entonces el frontend ve los mismos números y sabe de cuándo son
        assertThat(stale.stale()).isTrue();
        assertThat(stale.storage()).isEqualTo(fresh.storage());
        assertThat(stale.requests()).isEqualTo(fresh.requests());
        assertThat(stale.generatedAt()).isEqualTo(fresh.generatedAt());
    }

    @Test
    @DisplayName("Dado la foto de la caché, cuando se marca como stale, entonces la original sigue sin stale")
    void cachedSnapshotIsNeverChanged() {
        // Dado la foto compartida por todas las peticiones
        StorageStats cached = snapshot();

        // Cuando una petición la sirve como stale
        cached.asStale();

        // Entonces la caché no cambia: la siguiente lectura correcta no arrastra el stale
        assertThat(cached.stale()).isFalse();
    }

    @Test
    @DisplayName("Dado una foto ya stale, cuando se vuelve a marcar, entonces sigue igual")
    void markingTwiceChangesNothing() {
        // Dado una foto ya servida como stale (OCI sigue caído en la siguiente petición)
        StorageStats stale = snapshot().asStale();

        // Cuando se vuelve a marcar / Entonces es igual a la anterior
        assertThat(stale.asStale()).isEqualTo(stale);
    }

    private static StorageStats snapshot() {
        return new StorageStats(
                new StorageStats.Storage("STANDARD", 71_413L, 10_737_418_240L, 11L,
                        Instant.parse("2026-10-14T20:30:00Z")),
                new StorageStats.Requests(Instant.parse("2026-10-01T00:00:00Z"), 50_000L,
                        182L, 120L, 30L, 2L, 0L, 0L, 30L),
                Instant.parse("2026-10-14T21:30:00Z"),
                false);
    }
}
