package com.nocountry.simulation.communitylab.infrastructure.config.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.infrastructure.exception.LlmNotConfiguredException;

/**
 * Unit tests for the LLM fail-fast validator (no Spring, no network).
 * Derivado de: spec 002 RF-05 (fundamental provider must fail fast with a
 * clear message instead of dying on placeholder resolution or burning timeouts).
 */
@DisplayName("FailEnvModelConfiguration")
class FailEnvModelConfigurationTest {

    @Test
    @DisplayName("Given blank env values, when validated, then it fails fast with LLM_NOT_CONFIGURED")
    void failsFastOnBlankEnv() {
        // Given set-but-empty provider values
        FailEnvModelConfiguration config = new FailEnvModelConfiguration("  ", "m", "u");

        // When validated then immediate, typed failure
        assertThatThrownBy(config::validateStatusLLM)
                .isInstanceOf(LlmNotConfiguredException.class)
                .hasMessageContaining("LLM_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("Given all env values, when validated, then it passes silently")
    void passesWithAllEnv() {
        // Given a fully configured provider
        FailEnvModelConfiguration config = new FailEnvModelConfiguration("k", "m", "u");

        // When validated then no throw (boot continues)
        assertThatCode(config::validateStatusLLM).doesNotThrowAnyException();
    }
}
