package com.nocountry.simulation.communitylab.application.dtos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult.Status;

/**
 * Unit tests for the result the artifact store hands back to the flush (no Spring).
 *
 * <p>Derivado de: spec 006 RF-03 (1 PAR read por lote), RF-05 + enmienda 2026-10-10
 * (412 = ya guardado, con PAR nueva), RF-06 (el aviso lleva {@code parUrlBase + expiresAt}),
 * RNF-06 (la PAR nunca en logs) + plan 006 §3 (estado único {@code STORED|ALREADY_STORED|FAILED}).
 */
@DisplayName("StoreResult")
class StoreResultTest {

    // Distinctive token so a leak through toString cannot be a coincidence.
    private static final String PAR_URL =
            "https://objectstorage.sa-saopaulo-1.oraclecloud.com/p/SECRET-PAR-TOKEN/n/ns/b/MessagesUsers/o/paquetes/discord/paquete-b-1.json";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-11T10:00:00Z");

    // ---------- factories ----------

    @Test
    @DisplayName("Dado un PUT nuevo con su PAR, cuando se crea stored, entonces es ok, no deduped y lleva URL y caducidad")
    void storedIsOkWithParAndNotDeduped() {
        // Cuando el adapter guarda un objeto nuevo y crea su PAR
        StoreResult result = StoreResult.stored(PAR_URL, EXPIRES_AT);

        // Entonces el lote se puede descartar y el aviso tiene qué enviar
        assertThat(result.status()).isEqualTo(Status.STORED);
        assertThat(result.ok()).isTrue();
        assertThat(result.deduped()).isFalse();
        assertThat(result.parUrl()).isEqualTo(PAR_URL);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("Dado un objeto ya existente (412) con PAR nueva, cuando se crea alreadyStored, entonces es ok y deduped con URL")
    void alreadyStoredIsOkDedupedWithPar() {
        // Cuando el re-flush encuentra el objeto y crea otra PAR (enmienda RF-05)
        StoreResult result = StoreResult.alreadyStored(PAR_URL, EXPIRES_AT);

        // Entonces sigue siendo un éxito (el lote no queda zombi en pending) y marca la idempotencia
        assertThat(result.status()).isEqualTo(Status.ALREADY_STORED);
        assertThat(result.ok()).isTrue();
        assertThat(result.deduped()).isTrue();
        assertThat(result.parUrl()).isEqualTo(PAR_URL);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("Dado un fallo de OCI, cuando se crea failed, entonces no es ok, no es deduped y no lleva PAR")
    void failedIsNotOkAndCarriesNoPar() {
        // Cuando OCI falla (PUT o PAR)
        StoreResult result = StoreResult.failed();

        // Entonces el lote queda pendiente y no hay nada que avisar
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.ok()).isFalse();
        assertThat(result.deduped()).isFalse();
        assertThat(result.parUrl()).isNull();
        assertThat(result.expiresAt()).isNull();
    }

    // ---------- invariants (compact constructor) ----------

    @Test
    @DisplayName("Dado un status nulo, cuando se construye, entonces se rechaza")
    void nullStatusIsRejected() {
        // Cuando se construye sin estado / Entonces nunca existe un resultado ambiguo
        assertThatThrownBy(() -> new StoreResult(null, PAR_URL, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Dado un STORED sin URL, cuando se construye, entonces se rechaza para no avisar con parUrlBase vacía")
    void storedWithoutParUrlIsRejected() {
        // Cuando OCI devolviera la PAR sin URL / Entonces el error salta aquí, no en el frontend
        assertThatThrownBy(() -> StoreResult.stored(null, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Dado un STORED sin caducidad, cuando se construye, entonces se rechaza")
    void storedWithoutExpiresAtIsRejected() {
        // Cuando falta expiresAt / Entonces el aviso nunca sale sin fecha de caducidad
        assertThatThrownBy(() -> StoreResult.stored(PAR_URL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Dado un ALREADY_STORED sin URL o sin caducidad, cuando se construye, entonces se rechaza")
    void alreadyStoredWithoutParIsRejected() {
        // Cuando al 412 le falta la PAR nueva / Entonces misma regla que STORED
        assertThatThrownBy(() -> StoreResult.alreadyStored(null, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreResult.alreadyStored(PAR_URL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Dado un FAILED sin URL ni caducidad, cuando se construye, entonces se acepta")
    void failedWithoutParIsAccepted() {
        // Cuando falla OCI no hay PAR que llevar / Entonces los nulos son válidos solo aquí
        assertThatCode(() -> new StoreResult(Status.FAILED, null, null)).doesNotThrowAnyException();
    }

    // ---------- no PAR in logs ----------

    @Test
    @DisplayName("Dado un resultado con PAR, cuando se imprime (log, assert, debugger), entonces la URL no aparece")
    void toStringMasksParUrl() {
        // Dado un resultado con la URL secreta
        StoreResult result = StoreResult.stored(PAR_URL, EXPIRES_AT);

        // Cuando se convierte a texto
        String text = result.toString();

        // Entonces sigue siendo útil para depurar, pero sin el token (RNF-06)
        assertThat(text).contains("STORED", EXPIRES_AT.toString());
        assertThat(text).doesNotContain(PAR_URL, "SECRET-PAR-TOKEN");
    }
}
