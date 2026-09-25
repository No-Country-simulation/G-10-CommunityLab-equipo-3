package com.nocountry.simulation.communitylab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;

/**
 * Unit tests for the pure domain entity factory (no Spring).
 * Mirrors {@code Comment}: Dado/Cuando/Entonces.
 */
@DisplayName("Comment")
class CommentTest {

    private static final Instant SENT = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    @DisplayName("Given padded text, when validated, then it is trimmed without truncation")
    void trimsContent() {
        // Given padded content
        var comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", "  hola  ", SENT, Source.DISCORD);

        // Then trimmed, intact
        assertThat(comment.content()).isEqualTo("hola");
        assertThat(comment.truncated()).isFalse();
    }

    @Test
    @DisplayName("Given blank content, when validated, then it is rejected")
    void rejectsBlankContent() {
        assertThatThrownBy(() -> Comment.create(
                        "msg-1", "listen-123", "author-1", "author-name", "   ", SENT, Source.DISCORD))
                .isInstanceOf(InvalidCommentException.class);
    }

    @Test
    @DisplayName("Given null content, when validated, then it is rejected")
    void rejectsNullContent() {
        assertThatThrownBy(() -> Comment.create(
                        "msg-1", "listen-123", "author-1", "author-name", null, SENT, Source.DISCORD))
                .isInstanceOf(InvalidCommentException.class);
    }

    @Test
    @DisplayName("Given 3500 chars, when validated, then it is truncated to 2000 with flag")
    void truncatesLongContent() {
        var comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", "a".repeat(3500), SENT, Source.DISCORD);

        assertThat(comment.content()).hasSize(2000);
        assertThat(comment.truncated()).isTrue();
    }

    @Test
    @DisplayName("Given exact 2000 chars, when validated, then it is kept intact")
    void keepsExactLength() {
        String content = "a".repeat(2000);

        var comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", content, SENT, Source.DISCORD);

        assertThat(comment.content()).isEqualTo(content);
        assertThat(comment.truncated()).isFalse();
    }

    @Test
    @DisplayName("Given emoji at cut boundary, when validated, then the pair is not split")
    void doesNotSplitEmoji() {
        String raw = "a".repeat(1999) + "\uD83D\uDE00" + "b".repeat(10);

        var comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", raw, SENT, Source.DISCORD);

        assertThat(comment.content()).hasSize(1999);
        assertThat(comment.truncated()).isTrue();
    }

    @Test
    @DisplayName("Given trace ids, when validated, then channel and source are preserved")
    void preservesTrace() {
        var comment = Comment.create(
                "msg-1", "listen-123", "author-1", "author-name", "hello", SENT, Source.DISCORD);

        assertThat(comment.messageId()).isEqualTo("msg-1");
        assertThat(comment.channelId()).isEqualTo("listen-123");
        assertThat(comment.source()).isEqualTo(Source.DISCORD);
        assertThat(comment.sentTime()).isEqualTo(SENT);
    }

    @Test
    @DisplayName("Given missing ids, when validated, then it is rejected")
    void rejectsMissingIds() {
        assertThatThrownBy(() -> Comment.create(
                        null, "listen-123", "author-1", "author-name", "hello", SENT, Source.DISCORD))
                .isInstanceOf(InvalidCommentException.class);
        assertThatThrownBy(() -> Comment.create(
                        "msg-1", "listen-123", " ", "author-name", "hello", SENT, Source.DISCORD))
                .isInstanceOf(InvalidCommentException.class);
        assertThatThrownBy(() -> Comment.create(
                        "msg-1", "listen-123", "author-1", "author-name", "hello", null, Source.DISCORD))
                .isInstanceOf(InvalidCommentException.class);
        assertThatThrownBy(() -> Comment.create(
                        "msg-1", "listen-123", "author-1", "author-name", "hello", SENT, null))
                .isInstanceOf(InvalidCommentException.class);
    }
}
