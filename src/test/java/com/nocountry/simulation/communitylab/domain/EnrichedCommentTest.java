package com.nocountry.simulation.communitylab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;

/**
 * Unit tests for the redefined post-with-metadata record (no Spring).
 * Mirrors {@code spec 003 v1.7-single-post}: Dado/Cuando/Entonces.
 * <p>
 * Scope: construction contract as implemented (trace + post + metadata,
 * topics default, relevance clamp). Per-channel rejection rules arrive with
 * the migrated {@code Asset} validation.
 */
@DisplayName("EnrichedComment")
class EnrichedCommentTest {

    private static final Instant SENT = Instant.parse("2026-09-24T10:00:00Z");

    private static EnrichedComment post(
            Channels channelPost, String titlePost, String copy,
            List<String> hashtags, String cta,
            Sentiment sentiment, MessageType messageType,
            List<String> topics, int relevance, String flag) {
        return new EnrichedComment(
                "batch-1", "msg-1", "listen-123", "author-1", "author-name",
                "Conseguí empleo como dev Java", sentiment, Language.ES, messageType,
                topics, relevance, flag, EnrichedComment.PROMPT_VERSION,
                SENT, Source.DISCORD,
                channelPost, titlePost, copy, hashtags, cta);
    }

    private static EnrichedComment linkedin(String copy, List<String> hashtags, String cta) {
        return post(
                Channels.LINKEDIN, null, copy, hashtags, cta,
                Sentiment.POSITIVO, MessageType.LOGRO, List.of("empleo"), 75, null);
    }

    private static EnrichedComment x(String copy, List<String> hashtags) {
        return post(
                Channels.X, null, copy, hashtags, null,
                Sentiment.NEUTRAL, MessageType.COMENTARIO, List.of("saludo"), 40, null);
    }

    private static EnrichedComment faq(String titlePost, String copy, List<String> hashtags, String cta) {
        return post(
                Channels.FAQ, titlePost, copy, hashtags, cta,
                Sentiment.NEUTRAL, MessageType.DUDA, List.of("duda"), 65, null);
    }

    @Test
    @DisplayName("Given LinkedIn post data, when built, then post fields and trace are preserved")
    void preservesLinkedinPostAndTrace() {
        // Given a LinkedIn post with full traceability
        var enriched = post(
                Channels.LINKEDIN, null, "a".repeat(100), List.of("#ONE", "#EmpleoTech"),
                "Comparte tu historia",
                Sentiment.POSITIVO, MessageType.LOGRO, List.of("empleo", "java"), 75, null);

        // Then post, metadata and trace preserved
        assertThat(enriched.channelPost()).isEqualTo(Channels.LINKEDIN);
        assertThat(enriched.copy()).hasSize(100);
        assertThat(enriched.hashtags()).containsExactly("#ONE", "#EmpleoTech");
        assertThat(enriched.cta()).isEqualTo("Comparte tu historia");
        assertThat(enriched.sentiment()).isEqualTo(Sentiment.POSITIVO);
        assertThat(enriched.messageType()).isEqualTo(MessageType.LOGRO);
        assertThat(enriched.topics()).containsExactly("empleo", "java");
        assertThat(enriched.relevance()).isEqualTo(75);
        assertThat(enriched.flag()).isNull();
        assertThat(enriched.messageId()).isEqualTo("msg-1");
        assertThat(enriched.batchidLote()).isEqualTo("batch-1");
        assertThat(enriched.authorId()).isEqualTo("author-1");
        assertThat(enriched.promptVersion()).isEqualTo(EnrichedComment.PROMPT_VERSION);
        assertThat(enriched.sentTime()).isEqualTo(SENT);
        assertThat(enriched.source()).isEqualTo(Source.DISCORD);
    }

    @Test
    @DisplayName("Given X post data, when built, then channel and copy are preserved")
    void preservesXPost() {
        // Given an X post at the character limit
        var enriched = post(
                Channels.X, null, "a".repeat(280), List.of("#ONE"), null,
                Sentiment.NEUTRAL, MessageType.COMENTARIO, List.of("saludo"), 40, null);

        // Then preserved
        assertThat(enriched.channelPost()).isEqualTo(Channels.X);
        assertThat(enriched.copy()).hasSize(280);
        assertThat(enriched.hashtags()).containsExactly("#ONE");
    }

    @Test
    @DisplayName("Given fallback flag, when built, then flag and zero relevance are preserved")
    void preservesFallbackFlag() {
        // Given an LLM failure trace
        var enriched = post(
                Channels.FAQ, "Pregunta", "word ".repeat(50).trim(), List.of(), null,
                Sentiment.NEUTRAL, MessageType.OTRO, List.of(), 0,
                EnrichedComment.LLM_FALLBACK);

        // Then flagged trace preserved
        assertThat(enriched.flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(enriched.relevance()).isZero();
        assertThat(enriched.topics()).isEmpty();
    }

    @Test
    @DisplayName("Given null topics, when built, then they default to empty")
    void defaultsNullTopics() {
        // Given null topics from the model
        var enriched = post(
                Channels.X, null, "hello", List.of("#ONE"), null,
                Sentiment.NEUTRAL, MessageType.OTRO, null, 10, null);

        // Then empty by construction (contract stays valid)
        assertThat(enriched.topics()).isEmpty();
    }

    @Test
    @DisplayName("Given mutable topics, when built, then they are defensively copied")
    void copiesTopicsDefensively() {
        // Given a mutable topics list
        var topics = new ArrayList<>(List.of("java"));

        // When built
        var enriched = post(
                Channels.X, null, "hello", List.of("#ONE"), null,
                Sentiment.NEUTRAL, MessageType.OTRO, topics, 10, null);

        // Then later mutations do not leak in
        topics.add("oci");
        assertThat(enriched.topics()).containsExactly("java");
    }

    @Test
    @DisplayName("Given out-of-range relevance, when built, then it is clamped 0..100")
    void clampsRelevance() {
        // Given proposals outside the valid range
        var high = post(
                Channels.X, null, "hello", List.of("#ONE"), null,
                Sentiment.POSITIVO, MessageType.LOGRO, List.of(), 150, null);
        var low = post(
                Channels.X, null, "hello", List.of("#ONE"), null,
                Sentiment.NEUTRAL, MessageType.OTRO, List.of(), -5, null);

        // Then clamped by construction
        assertThat(high.relevance()).isEqualTo(100);
        assertThat(low.relevance()).isZero();
    }

    @Test
    @DisplayName("Given short LinkedIn copy, when built, then it is rejected")
    void rejectsShortLinkedinCopy() {
        // Given a LinkedIn draft below the lower bound
        assertThatThrownBy(() -> linkedin("a".repeat(40), List.of("#ONE", "#EmpleoTech"), "cta"))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given long LinkedIn copy, when built, then it is rejected")
    void rejectsLongLinkedinCopy() {
        // Given a LinkedIn draft above the upper bound
        assertThatThrownBy(() -> linkedin("a".repeat(601), List.of("#ONE", "#EmpleoTech"), "cta"))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given out-of-range LinkedIn hashtags, when built, then they are rejected")
    void rejectsLinkedinHashtagCount() {
        // Given tag counts outside 2..5
        assertThatThrownBy(() -> linkedin("a".repeat(100), List.of("#ONE"), "cta"))
                .isInstanceOf(InvalidAssetException.class);
        assertThatThrownBy(() -> linkedin("a".repeat(100),
                        List.of("#1", "#2", "#3", "#4", "#5", "#6"), "cta"))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given blank LinkedIn cta, when built, then it is rejected")
    void rejectsBlankLinkedinCta() {
        // Given a LinkedIn draft without call to action
        assertThatThrownBy(() -> linkedin("a".repeat(100), List.of("#ONE", "#TWO"), "  "))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given 281-char X post, when built, then it is rejected")
    void rejectsLongXCopy() {
        // Given an X draft above 280 chars
        assertThatThrownBy(() -> x("a".repeat(281), List.of("#ONE")))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given out-of-range X hashtags, when built, then they are rejected")
    void rejectsXHashtagCount() {
        // Given tag counts outside 1..2
        assertThatThrownBy(() -> x("hello", List.of()))
                .isInstanceOf(InvalidAssetException.class);
        assertThatThrownBy(() -> x("hello", List.of("#1", "#2", "#3")))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given 99-word newsletter, when built, then it is rejected")
    void rejectsShortNewsletter() {
        // Given a newsletter draft below 100 words
        assertThatThrownBy(() -> post(
                        Channels.NEWSLETTER, "Titulo", words(99), List.of("#1", "#2", "#3"), null,
                        Sentiment.NEUTRAL, MessageType.COMENTARIO, List.of("t"), 50, null))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given curated FAQ question, when built, then title and copy are preserved")
    void preservesFaqQuestion() {
        // Given a community doubt redacted as a question (never an answer)
        var enriched = faq(
                "Despliegue en OCI: error 401",
                "¿Cómo desplegar una API en OCI cuando aparece el error 401?",
                List.of("#OCI"), null);

        // Then question post preserved with mandatory title and no CTA
        assertThat(enriched.channelPost()).isEqualTo(Channels.FAQ);
        assertThat(enriched.titlePost()).isEqualTo("Despliegue en OCI: error 401");
        assertThat(enriched.cta()).isNull();
    }

    @Test
    @DisplayName("Given out-of-range FAQ copy, when built, then it is rejected")
    void rejectsOutOfRangeFaqCopy() {
        // Given FAQ drafts outside 20..500 chars
        assertThatThrownBy(() -> faq("Titulo pregunta", "a".repeat(19), List.of(), null))
                .isInstanceOf(InvalidAssetException.class);
        assertThatThrownBy(() -> faq("Titulo pregunta", "a".repeat(501), List.of(), null))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given blank FAQ title, when built, then it is rejected")
    void rejectsBlankFaqTitle() {
        // Given a curated question without its mandatory question title
        assertThatThrownBy(() -> faq("  ", "¿Cómo desplegar una API en OCI?", List.of(), null))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given FAQ cta, when built, then it is rejected")
    void rejectsFaqCta() {
        // Given a question carrying a call to action (questions carry none)
        assertThatThrownBy(() -> faq(
                        "Titulo pregunta", "¿Cómo desplegar una API en OCI?", List.of(), "Comenta aquí"))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given blank copy, when built, then it is rejected")
    void rejectsBlankCopy() {
        // Given an empty draft without fallback flag
        assertThatThrownBy(() -> x("   ", List.of("#ONE")))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given null channel, when built, then it is rejected")
    void rejectsNullChannel() {
        // Given no post channel and no fallback flag
        assertThatThrownBy(() -> post(
                        null, null, "hello", List.of("#ONE"), null,
                        Sentiment.NEUTRAL, MessageType.OTRO, List.of(), 10, null))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given missing trace data, when built, then it is rejected")
    void rejectsMissingTrace() {
        // Given blank or null messageId with an otherwise valid post
        assertThatThrownBy(() -> new EnrichedComment(
                        "batch-1", "  ", "listen-123", "author-1", "author-name",
                        "content", Sentiment.NEUTRAL, Language.ES, MessageType.OTRO,
                        List.of(), 10, null, EnrichedComment.PROMPT_VERSION,
                        SENT, Source.DISCORD,
                        Channels.X, null, "hello", List.of("#ONE"), null))
                .isInstanceOf(InvalidAssetException.class);
        assertThatThrownBy(() -> new EnrichedComment(
                        "batch-1", null, "listen-123", "author-1", "author-name",
                        "content", Sentiment.NEUTRAL, Language.ES, MessageType.OTRO,
                        List.of(), 10, null, EnrichedComment.PROMPT_VERSION,
                        SENT, Source.DISCORD,
                        Channels.X, null, "hello", List.of("#ONE"), null))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Given fallback flag with empty copy, when built, then it is kept as auditable trace")
    void keepsFallbackTrace() {
        // Given an LLM failure trace (empty copy, fallback flag)
        var enriched = post(
                Channels.FAQ, "", "", List.of(), "",
                Sentiment.NEUTRAL, MessageType.OTRO, List.of(), 0,
                EnrichedComment.LLM_FALLBACK);

        // Then post rules are skipped but trace and flag preserved
        assertThat(enriched.flag()).isEqualTo(EnrichedComment.LLM_FALLBACK);
        assertThat(enriched.relevance()).isZero();
        assertThat(enriched.messageId()).isEqualTo("msg-1");
    }

    private static String words(int n) {
        return "word ".repeat(n).trim();
    }
}
