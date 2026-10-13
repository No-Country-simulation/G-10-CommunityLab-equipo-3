package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Degraded store used when {@code OCI_AUTH_MODE=none}: flushes keep failing softly (batch stays pending)
 * and revisions are refused as "not configured", never as "not found".
 *
 * <p>Derivado de: spec 006 RF-07 (sin OCI el lote queda pendiente) + spec 007 RF-09 ({@code OCI_AUTH_MODE=none}
 * → {@code 503 OCI_NOT_CONFIGURED}, el contexto no cae) + plan 007 §1.2 ({@code ArtifactStoreAdapter}).
 */
@DisplayName("ArtifactStoreAdapter (OCI_AUTH_MODE=none)")
class ArtifactStoreAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final ArtifactStoreAdapter adapter = new ArtifactStoreAdapter();

    private static AssetPackage pkg() {
        EnrichedComment post = new EnrichedComment(
                "batch-1", "m-1", "channel-1", "author-1", "author-name", "content",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of(), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.X, null, "Nuevo empleo en Java", List.of("#Java"), null, false, 1);
        return AssetPackage.of("batch-1", Source.DISCORD, List.of(post), NOW);
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se hace flush, entonces failed y el lote sigue pendiente")
    void saveFailsSoftly() {
        // Dado / Cuando / Entonces sin excepción: el flush no debe tumbar el scheduler
        assertThat(adapter.save(pkg())).isEqualTo(StoreResult.failed());
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se carga para revisar, entonces no configurado (nunca vacío = 404)")
    void loadIsNotConfigured() {
        // Dado / Cuando / Entonces 503 en el handler
        assertThatThrownBy(() -> adapter.load(Source.DISCORD, "batch-1"))
                .isInstanceOf(StorageNotConfiguredException.class);
    }

    @Test
    @DisplayName("Dado OCI sin configurar, cuando se reescribe, entonces no configurado")
    void replaceIsNotConfigured() {
        // Dado / Cuando / Entonces 503 en el handler
        assertThatThrownBy(() -> adapter.replace(pkg(), "etag-1"))
                .isInstanceOf(StorageNotConfiguredException.class);
    }
}
