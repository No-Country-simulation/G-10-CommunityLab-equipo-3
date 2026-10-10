package com.nocountry.simulation.communitylab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.PackageStats;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;

/**
 * Unit tests for the package that a sealed batch becomes before upload (no Spring).
 *
 * <p>Derivado de: spec 006 RF-01 (1 lote = 1 objeto, vacío no se sube) + §4 Paquete
 * (stats, promptVersion) + lote por fuente (key {@code {source}/paquete-{batchId}.json}).
 */
@DisplayName("AssetPackage")
class AssetPackageTest {

    private static final String BATCH_ID = "batch-1";
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Test
    @DisplayName("Dado un lote DISCORD, cuando se pide su nombre de objeto, entonces cuelga de la carpeta discord")
    void objectNameUsesDiscordFolder() {
        // Dado un paquete de Discord
        AssetPackage pkg = AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post("m-1", Source.DISCORD)), NOW);

        // Cuando se pide el nombre / Entonces carpeta por fuente + batchId del lote sellado
        assertThat(pkg.objectName()).isEqualTo("discord/paquete-batch-1.json");
    }

    @Test
    @DisplayName("Dado un lote TELEGRAM, cuando se pide su nombre de objeto, entonces cuelga de la carpeta telegram")
    void objectNameUsesTelegramFolder() {
        // Dado un paquete de Telegram
        AssetPackage pkg = AssetPackage.of(BATCH_ID, Source.TELEGRAM, List.of(post("m-1", Source.TELEGRAM)), NOW);

        // Cuando se pide el nombre / Entonces nunca colisiona con la carpeta de Discord
        assertThat(pkg.objectName()).isEqualTo("telegram/paquete-batch-1.json");
    }

    @Test
    @DisplayName("Dado posts y trazas LLM_FALLBACK, cuando se construye, entonces las stats separan ambos")
    void statsCountFallbackApart() {
        // Dado 2 posts válidos y 1 traza fallback
        List<EnrichedComment> records = List.of(
                post("m-1", Source.DISCORD), post("m-2", Source.DISCORD), fallback("m-3"));

        // Cuando se construye por la fábrica
        AssetPackage pkg = AssetPackage.of(BATCH_ID, Source.DISCORD, records, NOW);

        // Entonces received=3, fallback=1 y los assets solo cuentan los posts reales
        assertThat(pkg.stats()).isEqualTo(new PackageStats(3, 2, 1, 2));
    }

    @Test
    @DisplayName("Dado un lote válido, cuando se construye, entonces conserva batchId, fuente, fecha, registros y promptVersion")
    void factoryKeepsEnvelopeFields() {
        // Dado un lote de un registro
        EnrichedComment record = post("m-1", Source.DISCORD);

        // Cuando se construye
        AssetPackage pkg = AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(record), NOW);

        // Entonces el envelope es el del lote sellado
        assertThat(pkg.batchId()).isEqualTo(BATCH_ID);
        assertThat(pkg.source()).isEqualTo(Source.DISCORD);
        assertThat(pkg.generatedAt()).isEqualTo(NOW);
        assertThat(pkg.enriched()).containsExactly(record);
        assertThat(pkg.promptVersion()).isEqualTo(EnrichedComment.PROMPT_VERSION);
    }

    @Test
    @DisplayName("Dado una lista mutable, cuando se modifica tras construir, entonces el paquete no cambia")
    void enrichedIsDefensiveCopy() {
        // Dado una lista mutable de registros
        List<EnrichedComment> records = new ArrayList<>(List.of(post("m-1", Source.DISCORD)));
        AssetPackage pkg = AssetPackage.of(BATCH_ID, Source.DISCORD, records, NOW);

        // Cuando el llamador la modifica después
        records.add(post("m-2", Source.DISCORD));

        // Entonces el paquete conserva lo que se selló y no admite cambios
        assertThat(pkg.enriched()).hasSize(1);
        assertThatThrownBy(() -> pkg.enriched().add(post("m-3", Source.DISCORD)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Dado un lote vacío, cuando se construye, entonces se rechaza (vacío no se sube)")
    void emptyBatchRejected() {
        assertThatThrownBy(() -> AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(), NOW))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Dado registros nulos, cuando se construye, entonces se rechaza sin NPE")
    void nullRecordsRejected() {
        assertThatThrownBy(() -> AssetPackage.of(BATCH_ID, Source.DISCORD, null, NOW))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Dado batchId en blanco, cuando se construye, entonces se rechaza")
    void blankBatchIdRejected() {
        assertThatThrownBy(() -> AssetPackage.of(" ", Source.DISCORD, List.of(post("m-1", Source.DISCORD)), NOW))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Dado fuente nula, cuando se construye, entonces se rechaza (no hay carpeta a la que subir)")
    void nullSourceRejected() {
        assertThatThrownBy(() -> AssetPackage.of(BATCH_ID, null, List.of(post("m-1", Source.DISCORD)), NOW))
                .isInstanceOf(InvalidAssetException.class);
    }

    private static EnrichedComment post(String messageId, Source source) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, null, NOW, source,
                Channels.FAQ, "Primer empleo dev",
                "Consegui mi primer empleo como dev Java gracias a la comunidad",
                List.of("#EmpleoTech"), null, false, 1);
    }

    private static EnrichedComment fallback(String messageId) {
        return new EnrichedComment(
                BATCH_ID, messageId, "channel-1", "author-1", "tester",
                null, Sentiment.NEUTRAL, null, MessageType.OTRO, List.of(), 0,
                EnrichedComment.LLM_FALLBACK, null, NOW, Source.DISCORD,
                Channels.FAQ, null, null, List.of(), null, false, 1);
    }
}
