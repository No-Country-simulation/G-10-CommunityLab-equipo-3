package com.nocountry.simulation.communitylab.infrastructure.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;

import net.dv8tion.jda.api.JDA;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;

/**
 * Real Tomcat (not MockMvc): errors raised with sendError go through the ERROR dispatch to /error,
 * which MockMvc never runs. With denyAll on that dispatch every 415/413/500 reached the client as an
 * empty 403, so the dashboard read "route closed" for what was a format or server error.
 *
 * <p>Derivado de: spec 007 RF-02 (códigos distinguibles por el frontend) + constitución Q3 ({@code denyAll} para
 * rutas no públicas, que sigue aplicando a {@code /error} pedido directamente) + revisión 2026-10-12.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Error dispatch on a real server")
class ErrorDispatchOnServerTest {

    private static final String PATCH_PATH = "/api/v1/discord/packages/b-1/messages/m-1";

    // Why: the real JDA bean must never hit the Discord gateway in tests.
    @MockitoBean
    private JDA jda;

    // Why: the Telegram long-polling starter would register the bot against the real API.
    @MockitoBean
    private TelegramBotInitializer telegramBotInitializer;

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

    @MockitoBean
    private RevisePostUseCase revisePostUseCase;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> patch(String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATCH_PATH))
                .header("Content-Type", contentType)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Dado un PATCH con Content-Type text/plain, cuando llega al servidor, entonces 415 y no 403")
    void unsupportedMediaTypeKeepsItsStatus() throws Exception {
        // Dado / Cuando un cliente envía texto plano
        HttpResponse<String> response = patch("text/plain", "approved");

        // Entonces el frontend ve el error real de formato
        assertThat(response.statusCode()).isEqualTo(415);
        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado un fallo no previsto del caso de uso, cuando llega al servidor, entonces 500 sin detalles y no 403")
    void unexpectedFailureIsA500() throws Exception {
        // Dado una excepción que ningún handler traduce, con un mensaje interno reconocible
        when(revisePostUseCase.revise(any())).thenThrow(new IllegalStateException("SECRET-INTERNAL-DETAIL"));

        // Cuando se envía una revisión válida
        HttpResponse<String> response = patch("application/json", "{\"expectedVersion\":1,\"approved\":\"APPROVED\"}");

        // Entonces el error del servidor no se disfraza de "ruta cerrada" ni expone el mensaje
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("SECRET-INTERNAL-DETAIL");
    }

    @Test
    @DisplayName("Dado un body de más de 16KB, cuando llega al servidor, entonces 413")
    void oversizedBodyIsA413() throws Exception {
        // Dado un texto que supera el límite del PATCH
        String body = "{\"expectedVersion\":1,\"outputContentProcessed\":\"" + "a".repeat(20_000) + "\"}";

        // Cuando se envía / Entonces se corta antes de leerlo y el caso de uso no se llama
        assertThat(patch("application/json", body).statusCode()).isEqualTo(413);
        verify(revisePostUseCase, never()).revise(any());
    }

    @Test
    @DisplayName("Dado /error pedido directamente, cuando llega, entonces sigue cerrado (403)")
    void errorPathRequestedDirectlyStaysDenied() throws Exception {
        // Dado / Cuando alguien pide /error como una ruta más
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).GET().build();

        // Entonces solo el dispatch interno de errores está permitido, no la ruta
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
