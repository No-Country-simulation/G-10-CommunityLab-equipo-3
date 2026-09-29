package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nocountry.simulation.communitylab.domain.enums.Channels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for raw LLM output hygiene (no Spring, no LLM).
 * Mirrors spec 003 RF-04/RNF-01: Dado/Cuando/Entonces.
 *
 * <p>Scope: fences, exact object cut (balanced scan respecting strings and
 * escapes), truncation semantics, null-safety. The service-level retry path
 * stays covered by {@code AnalyzeServiceTest} untouched.
 */
@DisplayName("LlmOutputSanitizer")
class LlmOutputSanitizerTest {

    private static final String VALID_JSON = """
            {"messageProcess":"Hola comunidad","language":"ES","sentiment":"NEUTRAL",\
            "messageType":"DUDA","topics":["oci"],"relevance":65,\
            "channelPost":"FAQ","titlePost":"Despliegue en OCI",\
            "copy":"¿Cómo desplegar una API en OCI?","hashtags":[],"cta":null}""";

    private final LlmOutputSanitizer sanitizer = new LlmOutputSanitizer();

    @Test
    @DisplayName("Given json fenced output, when converted, then fences are stripped and model is built")
    void convertsFencedJson() {
        // Given a fenced model answer (seen live)
        String raw = "```json\n" + VALID_JSON + "\n```";

        // When converted
        var model = sanitizer.convert(raw);

        // Then payload built
        assertThat(model.channelPost()).isEqualTo(Channels.FAQ);
        assertThat(model.copy()).isEqualTo("¿Cómo desplegar una API en OCI?");
        assertThat(model.titlePost()).isEqualTo("Despliegue en OCI");
    }

    @Test
    @DisplayName("Given same-line fence close, when converted, then it still converts")
    void convertsSameLineFenceClose() {
        // Given fences opened and closed without newlines
        String raw = "```json " + VALID_JSON + "```";

        // When converted
        var model = sanitizer.convert(raw);

        // Then payload built
        assertThat(model.channelPost()).isEqualTo(Channels.FAQ);
    }

    @Test
    @DisplayName("Given preamble and epilogue with braces, when converted, then only the object is taken")
    void cutsEpilogueWithBraces() {
        // Given chatter around the object, epilogue carrying braces (old
        // first-{-to-last-} cut swallowed it into the payload and failed)
        String raw = "Claro, aquí tienes {vale}\n" + VALID_JSON
                + "\nEso es todo {fin} ¡suerte!";

        // When converted
        var model = sanitizer.convert(raw);

        // Then exact object converted
        assertThat(model.titlePost()).isEqualTo("Despliegue en OCI");
        assertThat(model.copy()).isEqualTo("¿Cómo desplegar una API en OCI?");
    }

    @Test
    @DisplayName("Given braces inside string values, when converted, then balance is not confused")
    void ignoresBracesInsideStrings() {
        // Given payload braces inside a string value
        String withBraces = VALID_JSON.replace(
                "¿Cómo desplegar una API en OCI?",
                "Usa {id} así en tu llamada y listo ya");
        String raw = "Preámbulo {hola}\n" + withBraces + "\nEpílogo {adios}";

        // When converted
        var model = sanitizer.convert(raw);

        // Then inner braces preserved in the value
        assertThat(model.copy()).isEqualTo("Usa {id} así en tu llamada y listo ya");
    }

    @Test
    @DisplayName("Given escaped quotes inside strings, when converted, then the scan survives")
    void survivesEscapedQuotes() {
        // Given escaped quotes plus braces inside a string value
        String withEscapes = VALID_JSON.replace(
                "¿Cómo desplegar una API en OCI?",
                "Dijo 'hola' y siguió con {id} hasta el final del texto");
        String raw = withEscapes + "\nCola {cierre}";

        // When converted
        var model = sanitizer.convert(raw);

        // Then value intact
        assertThat(model.copy()).startsWith("Dijo 'hola'");
    }

    @Test
    @DisplayName("Given truncated output, when converted, then it fails preserving failure semantics")
    void truncatedStillFails() {
        // Given a really truncated object (no closing brace)
        String truncated = VALID_JSON.substring(0, VALID_JSON.length() - 40);

        // When sanitized the text is returned as-is for the converter to report
        assertThat(sanitizer.sanitize(truncated)).isEqualTo(truncated);

        // Then conversion fails (truncation still surfaces, never silent)
        assertThatThrownBy(() -> sanitizer.convert(truncated))
                .isInstanceOf(RuntimeException.class);

        // And the fingerprint still classifies it as unterminated
        assertThat(LlmOutputSanitizer.fingerprint(truncated)).contains("endsBrace=false");
    }

    @Test
    @DisplayName("Given null or braceless input, when sanitized, then null-safe passthrough holds")
    void nullSafePassthrough() {
        // Given degenerate inputs
        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.sanitize("sin llaves")).isEqualTo("sin llaves");
        assertThat(LlmOutputSanitizer.fingerprint(null)).isEqualTo("raw=null");

        // Then braceless conversion fails loud, never silent
        assertThatThrownBy(() -> sanitizer.convert("sin llaves"))
                .isInstanceOf(RuntimeException.class);
    }
}
