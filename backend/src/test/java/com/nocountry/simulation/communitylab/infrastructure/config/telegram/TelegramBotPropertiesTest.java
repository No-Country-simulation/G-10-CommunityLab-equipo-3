package com.nocountry.simulation.communitylab.infrastructure.config.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.infrastructure.exception.TelegramTokenMissingException;

/**
 * Unit tests for the Telegram fail-fast properties (no Spring, no network).
 * Derivado de: spec 005 RF-02,RNF-02 + plan §5 (token por env, sin token
 * falla rapido con error claro en vez de fingir UP).
 */
@DisplayName("TelegramBotProperties")
class TelegramBotPropertiesTest {

    @Test
    @DisplayName("Dado token nulo, cuando se crea, entonces falla rapido con TelegramTokenMissing")
    void failsFastOnNullToken() {
        // Dado un token ausente
        // Cuando se construye entonces falla de inmediato con excepcion tipada
        assertThatThrownBy(() -> new TelegramBotProperties(null, 555L))
                .isInstanceOf(TelegramTokenMissingException.class)
                .hasMessageContaining("Telegram bot token is missing");
    }

    @Test
    @DisplayName("Dado token en blanco, cuando se crea, entonces falla rapido con TelegramTokenMissing")
    void failsFastOnBlankToken() {
        // Dado un token solo-blancos
        // Cuando se construye entonces falla de inmediato sin arrancar el bot
        assertThatThrownBy(() -> new TelegramBotProperties("   ", 555L))
                .isInstanceOf(TelegramTokenMissingException.class)
                .hasMessageContaining("Telegram bot token is missing");
    }

    @Test
    @DisplayName("Dado token vacio, cuando se crea, entonces falla rapido con TelegramTokenMissing")
    void failsFastOnEmptyToken() {
        // Dado un token vacio
        // Cuando se construye entonces falla de inmediato
        assertThatThrownBy(() -> new TelegramBotProperties("", 555L))
                .isInstanceOf(TelegramTokenMissingException.class);
    }

    @Test
    @DisplayName("Dado token valido, cuando se crea, entonces preserva token y chatId sin lanzar")
    void passesWithValidToken() {
        // Dado un token y chatId validos por env
        // Cuando se construye entonces no lanza y preserva ambos valores
        assertThatCode(() -> new TelegramBotProperties("test-token", 555L))
                .doesNotThrowAnyException();

        var properties = new TelegramBotProperties("test-token", 555L);
        assertThat(properties.token()).isEqualTo("test-token");
        assertThat(properties.listenGroupId()).isEqualTo(555L);
    }
}
