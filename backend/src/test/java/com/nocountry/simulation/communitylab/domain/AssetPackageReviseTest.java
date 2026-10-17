package com.nocountry.simulation.communitylab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;

/**
 * Unit tests for revising one post inside a stored package (no Spring), and for the single
 * place where the object name is computed for {@code save/load/replace}.
 *
 * <p>Derivado de: spec 007 RF-02 ({@code 404} si el post no existe) + RF-03 ({@code LLM_FALLBACK}
 * no revisable) + RF-04 (no-op sin cambio) + plan 007 §1.2 (domain: {@code AssetPackage.revise},
 * {@code stats} intactas, {@code objectName(Source, String)} estático).
 */
@DisplayName("AssetPackage.revise")
class AssetPackageReviseTest {

    private static final String BATCH_ID = "batch-1";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static EnrichedComment post(String messageId) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "author-name",
                "Conseguí empleo como dev Java", Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 75, null, EnrichedComment.PROMPT_VERSION,
                NOW, Source.DISCORD,
                Channels.X, null, "Nuevo empleo en Java", List.of("#Java"), null, false, ApprovalStatus.IN_REVIEW, 1, null, null);
    }

    private static EnrichedComment fallback(String messageId) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "author-name",
                "content", Sentiment.NEUTRAL, Language.ES, MessageType.OTRO,
                List.of(), 0, EnrichedComment.LLM_FALLBACK, EnrichedComment.PROMPT_VERSION,
                NOW, Source.DISCORD,
                null, null, null, List.of(), null, false, ApprovalStatus.IN_REVIEW, 1, null, null);
    }

    // Two real posts and one LLM failure trace, as a flushed batch looks like.
    private static AssetPackage pkg() {
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post("m-1"), post("m-2"), fallback("m-3")), NOW);
    }

    private static PostChange approve() {
        return new PostChange(null, ApprovalStatus.APPROVED, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Dado un paquete de 3 registros, cuando se aprueba m-2, entonces solo m-2 cambia y el orden se conserva")
    void replacesOnlyTheRevisedPost() {
        // Dado un paquete guardado
        AssetPackage original = pkg();

        // Cuando se aprueba el segundo post
        AssetPackage revised = original.revise("m-2", approve());

        // Entonces m-2 sube de versión y los demás registros son idénticos, en el mismo sitio
        assertThat(revised.enriched()).extracting(EnrichedComment::messageId).containsExactly("m-1", "m-2", "m-3");
        assertThat(revised.enriched().get(1).approved()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(revised.enriched().get(1).versionMessage()).isEqualTo(2);
        assertThat(revised.enriched().get(0)).isEqualTo(original.enriched().get(0));
        assertThat(revised.enriched().get(2)).isEqualTo(original.enriched().get(2));
    }

    @Test
    @DisplayName("Dado un paquete, cuando se revisa un post, entonces envelope y stats no cambian")
    void keepsEnvelopeAndStats() {
        // Dado un paquete guardado
        AssetPackage original = pkg();

        // Cuando se revisa un post
        AssetPackage revised = original.revise("m-1", approve());

        // Entonces sigue siendo el mismo lote: mismo objeto en OCI, mismas cifras
        assertThat(revised.batchId()).isEqualTo(original.batchId());
        assertThat(revised.source()).isEqualTo(original.source());
        assertThat(revised.generatedAt()).isEqualTo(original.generatedAt());
        assertThat(revised.promptVersion()).isEqualTo(original.promptVersion());
        assertThat(revised.stats()).isEqualTo(original.stats());
        assertThat(revised.objectName()).isEqualTo(original.objectName());
    }

    @Test
    @DisplayName("Dado un paquete, cuando se revisa, entonces el paquete original no se modifica")
    void originalPackageIsNotMutated() {
        // Dado un paquete guardado
        AssetPackage original = pkg();

        // Cuando se revisa un post
        original.revise("m-1", approve());

        // Entonces el original sigue como se leyó (un reintento parte de una copia limpia)
        assertThat(original.enriched().get(0).approved()).isEqualTo(ApprovalStatus.IN_REVIEW);
        assertThat(original.enriched().get(0).versionMessage()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un post ya aprobado, cuando se vuelve a aprobar, entonces el paquete es igual al original")
    void noOpRevisionKeepsThePackageEqual() {
        // Dado un paquete con m-1 ya aprobado
        AssetPackage original = pkg().revise("m-1", approve());

        // Cuando se aprueba otra vez
        AssetPackage revised = original.revise("m-1", approve());

        // Entonces no hay nada que escribir
        assertThat(revised).isEqualTo(original);
    }

    @Test
    @DisplayName("Dado un messageId que no está en el paquete, cuando se revisa, entonces el post no existe")
    void unknownMessageIdIsNotFound() {
        // Dado un paquete sin m-9
        AssetPackage original = pkg();

        // Cuando se revisa m-9 / Entonces no encontrado (→ 404, sin escribir)
        assertThatThrownBy(() -> original.revise("m-9", approve()))
                .isInstanceOf(PostNotFoundException.class);
    }

    @Test
    @DisplayName("Dado un registro LLM_FALLBACK en el paquete, cuando se revisa, entonces no es revisable")
    void fallbackInsideThePackageIsNotRevisable() {
        // Dado un paquete con una traza de fallo
        AssetPackage original = pkg();

        // Cuando se intenta aprobar la traza / Entonces no revisable (→ 422)
        assertThatThrownBy(() -> original.revise("m-3", approve()))
                .isInstanceOf(PostNotRevisableException.class);
    }

    @Test
    @DisplayName("Dado una edición que rompe las reglas del canal, cuando se revisa, entonces se rechaza")
    void invalidRevisionIsRejected() {
        // Dado un paquete con posts X
        AssetPackage original = pkg();

        // Cuando el texto supera los 280 de X / Entonces la revisión no se construye (→ 400)
        assertThatThrownBy(() -> original.revise("m-1",
                        new PostChange(null, null, null, null, null, "a".repeat(300), null, null, null)))
                .isInstanceOf(InvalidAssetException.class);
    }

    // ---------- single place for the object name (save / load / replace) ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Source.class)
    @DisplayName("Dado una fuente y un batchId, cuando se calcula el nombre sin paquete, entonces coincide con el del paquete")
    void staticObjectNameMatchesTheInstanceOne(Source source) {
        // Dado el paquete que save subiría
        AssetPackage stored = AssetPackage.of(BATCH_ID, source, List.of(post("m-1")), NOW);

        // Cuando load/replace calculan el nombre solo con source + batchId
        String name = AssetPackage.objectName(source, BATCH_ID);

        // Entonces apuntan al mismo objeto (si difirieran, toda revisión daría 404)
        assertThat(name).isEqualTo(stored.objectName());
    }

    @Test
    @DisplayName("Dado DISCORD y batch-1, cuando se calcula el nombre, entonces es discord/paquete-batch-1.json")
    void staticObjectNameFormat() {
        // Dado / Cuando / Entonces la carpeta va en minúsculas, igual que en 006
        assertThat(AssetPackage.objectName(Source.DISCORD, BATCH_ID)).isEqualTo("discord/paquete-batch-1.json");
    }
}
