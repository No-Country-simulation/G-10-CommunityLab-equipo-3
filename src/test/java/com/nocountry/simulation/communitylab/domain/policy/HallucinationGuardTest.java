package com.nocountry.simulation.communitylab.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;

/**
 * Unit tests for the pure anti-hallucination guard (no Spring, no LLM).
 * Mirrors {@code spec 003 RF-05}: Dado/Cuando/Entonces.
 */
@DisplayName("HallucinationGuard")
class HallucinationGuardTest {

    @Test
    @DisplayName("Given clean copy, when checked, then it passes")
    void passesCleanCopy() {
        // Given a source and a copy without entities
        boolean result = HallucinationGuard.passes(
                "Conseguí empleo como dev Java, gracias comunidad!",
                "De la comunidad al primer empleo como dev Java");

        // Then it passes
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given source without company, when copy invents one, then it is blocked")
    void blocksInventedCompany() {
        // Given a source with no company and a copy inventing one
        boolean result = HallucinationGuard.passes(
                "Conseguí empleo como dev Java, gracias comunidad!",
                "Felicidades por tu empleo en Globant!");

        // Then blocked
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Given source with company, when copy repeats it, then it passes")
    void passesRepeatedCompany() {
        // Given a source naming a company, case-insensitively
        boolean result = HallucinationGuard.passes(
                "Entré a trabajar a globant como QA",
                "Felicidades por tu rol en GLOBANT");

        // Then it passes (normalization, not invention)
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given source without salary, when copy invents one, then it is blocked")
    void blocksInventedSalary() {
        // Given a source with no money and a copy inventing some
        assertThat(HallucinationGuard.passes(
                "Conseguí mi primer empleo IT",
                "Con sueldo de $5.000 USD!")).isFalse();
        assertThat(HallucinationGuard.passes(
                "Conseguí mi primer empleo IT",
                "Con aumento del 30%!")).isFalse();
    }

    @Test
    @DisplayName("Given source with salary, when copy repeats it, then it passes")
    void passesRepeatedSalary() {
        // Given a source stating the money value
        boolean result = HallucinationGuard.passes(
                "Me ofrecieron $5.000 USD mensuales",
                "Nuevo empleo por $5.000 USD!");

        // Then it passes (present in source, not invented)
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given source without date, when copy invents one, then it is blocked")
    void blocksInventedDate() {
        // Given a source with no date and a copy inventing one
        assertThat(HallucinationGuard.passes(
                "Aprendí Spring Boot este año",
                "Inscríbete antes del 12/03/2026!")).isFalse();
    }

    @Test
    @DisplayName("Given source with metric, when copy repeats it, then it passes")
    void passesRepeatedMetric() {
        // Given a source stating the metric
        boolean result = HallucinationGuard.passes(
                "Mejoré el tiempo de respuesta x2",
                "Logro: respuesta x2 más rápida!");

        // Then it passes
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given copy with word metas, when checked, then company meta does not block it")
    void ignoresMetaSubstring() {
        // Given a copy using `metas` (goals), not the company `meta`
        boolean result = HallucinationGuard.passes(
                "Cumplimos las metas del sprint en equipo",
                "Cumplimos nuestras metas, a por las siguientes!");

        // Then whole-word matching avoids the false positive
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given fallback flag, when evaluated, then it passes as auditable trace")
    void passesFallbackFlag() {
        // Given an LLM failure trace with empty copy
        boolean result = HallucinationGuard.passes(
                "Conseguí mi primer empleo IT", "", EnrichedComment.LLM_FALLBACK);

        // Then approved (auditable record, buffer without SSE downstream)
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given empty copy without flag, when evaluated, then it is blocked")
    void blocksEmptyCopyWithoutFlag() {
        // Given an empty generation without fallback flag (DRAFT_EMPTY path)
        boolean result = HallucinationGuard.passes(
                "Conseguí mi primer empleo IT", "", null);

        // Then blocked, nothing to publish
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Given null or blank inputs, when checked, then it fails safe to blocked")
    void failsSafeOnNullInputs() {
        // Given missing inputs there is nothing to verify against
        assertThat(HallucinationGuard.passes(null, "copy limpio")).isFalse();
        assertThat(HallucinationGuard.passes("fuente válida", null)).isFalse();
        assertThat(HallucinationGuard.passes("fuente válida", "   ")).isFalse();
    }
}
