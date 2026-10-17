package com.nocountry.simulation.communitylab.infrastructure.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Body cap for the revision PATCH, checked before Jackson reads anything (no Spring context).
 *
 * <p>Derivado de: spec 007 RNF-01 (body del {@code PATCH} {@code <= 16KB}) + revisión de seguridad 2026-10-12
 * (los {@code @Size} solo validan después de cargar el body entero: un body enorme agotaría el heap).
 */
@DisplayName("RequestBodyLimitFilter")
class RequestBodyLimitFilterTest {

    private static final int LIMIT = 16_384;

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    private static MockHttpServletRequest patch(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/discord/packages/b-1/messages/m-1");
        request.setContent(body);
        return request;
    }

    // Records whether the request went on and what the next filter could read.
    private static final class RecordingChain implements FilterChain {
        boolean called;
        ServletRequest request;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.called = true;
            this.request = request;
        }
    }

    @Test
    @DisplayName("Dado un PATCH declarado de más de 16KB, cuando llega, entonces 413 ProblemDetail sin leer el body ni seguir la cadena")
    void declaredOversizedBodyIsRejectedUpfront() throws Exception {
        // Dado un body de 16KB + 1 byte con Content-Length
        MockHttpServletRequest request = patch(new byte[LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        // Cuando pasa por el filtro
        filter.doFilter(request, response, chain);

        // Entonces se corta antes de Jackson y el frontend recibe el mismo formato que el resto de errores
        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":413");
    }

    @Test
    @DisplayName("Dado un PATCH de exactamente 16KB, cuando llega, entonces sigue la cadena")
    void bodyAtTheLimitPasses() throws Exception {
        // Dado un body justo en el límite
        RecordingChain chain = new RecordingChain();

        // Cuando pasa por el filtro / Entonces la validación normal decide
        filter.doFilter(patch(new byte[LIMIT]), new MockHttpServletResponse(), chain);
        assertThat(chain.called).isTrue();
    }

    @Test
    @DisplayName("Dado un PATCH sin Content-Length (chunked) que supera 16KB, cuando se lee, entonces la lectura se corta en el límite")
    void undeclaredOversizedBodyStopsAtTheLimit() throws Exception {
        // Dado un body chunked: no declara su tamaño, así que solo se puede cortar mientras se lee
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/discord/packages/b-1/messages/m-1") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(new byte[LIMIT * 4]);
        RecordingChain chain = new RecordingChain();

        // Cuando el siguiente filtro (Jackson) lee el body
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // Entonces nunca carga más de 16KB en memoria
        assertThat(chain.called).isTrue();
        assertThatThrownBy(() -> chain.request.getInputStream().readAllBytes())
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Dado un PATCH chunked dentro del límite, cuando se lee, entonces llega completo")
    void undeclaredSmallBodyIsReadWhole() throws Exception {
        // Dado un body chunked pequeño
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/discord/packages/b-1/messages/m-1") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent("{\"expectedVersion\":1,\"approved\":\"APPROVED\"}".getBytes());
        RecordingChain chain = new RecordingChain();

        // Cuando se lee / Entonces el contador no altera el contenido
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(new String(chain.request.getInputStream().readAllBytes()))
                .isEqualTo("{\"expectedVersion\":1,\"approved\":\"APPROVED\"}");
    }

    @Test
    @DisplayName("Dado un GET (el SSE), cuando llega, entonces el filtro no interviene")
    void otherMethodsAreNotFiltered() throws Exception {
        // Dado una petición que no es PATCH
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/discord/messages");
        RecordingChain chain = new RecordingChain();

        // Cuando pasa / Entonces sigue intacta
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.called).isTrue();
        assertThat(chain.request).isSameAs(request);
    }
}
