package com.nocountry.simulation.communitylab.application.services.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Mapping of the LLM output to the buffered record: the AI output is always
 * version 1 and pending approval. No Spring context, no mocks (pure mapping).
 *
 * <p>Derivado de: spec 007 RF-01 + plan 007 §1.1 (domain).
 */
@DisplayName("ConvertEnrichedCommentService")
class ConvertEnrichedCommentServiceTest {

    private static final Instant SENT = Instant.parse("2026-10-09T10:00:00Z");

    private final ConvertEnrichedCommentService service = new ConvertEnrichedCommentService();

    @Test
    @DisplayName("Dado un post generado por el LLM, cuando se construye el registro, entonces nace sin aprobar y en versión 1")
    void llmOutputStartsPendingAtVersionOne() {
        // Dado un mensaje y la respuesta válida del modelo
        ResponseModel response = new ResponseModel(
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Language.ES, Sentiment.POSITIVO, MessageType.LOGRO,
                List.of("empleo", "java"), 85,
                Channels.FAQ, "Primer empleo dev",
                "Conseguiste tu primer empleo como dev gracias a la comunidad?",
                List.of("#EmpleoTech"), null);

        // Cuando se convierte
        EnrichedComment enriched = service.constructMessage(comment(), response, "batch-1");

        // Entonces el usuario todavía no lo revisó
        assertThat(enriched.approved()).isFalse();
        assertThat(enriched.versionMessage()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un fallo del LLM, cuando se construye la traza auditable, entonces también nace sin aprobar y en versión 1")
    void fallbackTraceStartsPendingAtVersionOne() {
        // Dado la respuesta de respaldo del modelo
        // Cuando se convierte
        EnrichedComment enriched = service.constructMessage(comment(), ResponseModel.fallback(), "batch-1");

        // Entonces es una traza LLM_FALLBACK con los mismos valores por defecto
        assertThat(enriched.flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(enriched.approved()).isFalse();
        assertThat(enriched.versionMessage()).isEqualTo(1);
    }

    private static Comment comment() {
        return new Comment("msg-1", "channel-1", "author-1", "tester",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                SENT, false, Source.DISCORD);
    }
}
