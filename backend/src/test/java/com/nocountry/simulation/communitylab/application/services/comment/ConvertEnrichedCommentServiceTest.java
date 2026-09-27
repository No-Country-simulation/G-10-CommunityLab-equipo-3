package com.nocountry.simulation.communitylab.application.services.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Unit tests for the Comment + LLM response merge (no Spring).
 * Derivado de: spec 002 RF-01/RF-02/RF-03 (topics, relevance policy, fallback flag).
 */
@DisplayName("ConvertEnrichedCommentService")
class ConvertEnrichedCommentServiceTest {

    private static final Instant SENT = Instant.parse("2026-09-24T10:00:00Z");

    private final ConvertEnrichedCommentService service = new ConvertEnrichedCommentService();

    private static Comment comment(String content) {
        return Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", content, SENT, Source.DISCORD);
    }

    @Test
    @DisplayName("Given logro response, when merged, then relevance floor and batchidLote apply")
    void mergesWithPolicyFloor() {
        // Given a positive achievement analysis
        ResponseModel response = new ResponseModel(
                "Conseguí empleo como dev Java", Language.ES, Sentiment.POSITIVO,
                MessageType.LOGRO, List.of("empleo", "java"), 50);

        // When merged into the lot
        EnrichedComment enriched = service.constructMessage(
                comment("Conseguí empleo como dev Java, gracias comunidad!"), response, "batch-1");

        // Then trace preserved, policy floor applied, no flag
        assertThat(enriched.batchidLote()).isEqualTo("batch-1");
        assertThat(enriched.messageId()).isEqualTo("msg-1");
        assertThat(enriched.topics()).containsExactly("empleo", "java");
        assertThat(enriched.relevance()).isEqualTo(70);
        assertThat(enriched.flag()).isNull();
        assertThat(enriched.promptVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("Given fallback response, when merged, then flag set and relevance zero")
    void mergesFallbackWithFlag() {
        // Given an LLM failure object (null process, empty topics, zero)
        EnrichedComment enriched = service.constructMessage(
                comment("hello world, this is a test"), ResponseModel.fallback(), "batch-9");

        // Then flagged trace with zero relevance (short real texts keep null flag)
        assertThat(enriched.flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(enriched.relevance()).isZero();
        assertThat(enriched.topics()).isEmpty();
        assertThat(enriched.batchidLote()).isEqualTo("batch-9");
    }

    @Test
    @DisplayName("Given short real text, when merged, then relevance zero without flag")
    void shortTextHasNoFlag() {
        // Given a real (non-fallback) short analysis scoring zero
        ResponseModel response = new ResponseModel(
                "ok", Language.ES, Sentiment.NEUTRAL,
                MessageType.OTRO, List.of("saludo"), 40);

        // When merged
        EnrichedComment enriched = service.constructMessage(comment("ok"), response, "batch-1");

        // Then zero by length rule but no fallback flag (flag is born from the path)
        assertThat(enriched.relevance()).isZero();
        assertThat(enriched.flag()).isNull();
    }
}
