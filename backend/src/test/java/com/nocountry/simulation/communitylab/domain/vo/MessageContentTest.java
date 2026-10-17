package com.nocountry.simulation.communitylab.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;

/**
 * Unit tests for the minimum length rule of the content value object (no Spring).
 * Derivado de: spec 001 RF-02 (enmienda 2026-10-16, mínimo 25 chars) + RF-04 (trim) + spec 005 RF-02.
 * Truncation at MAX_LENGTH stays covered by {@code CommentTest}.
 */
@DisplayName("MessageContent minimum length")
class MessageContentTest {

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("Given one character below the minimum, when normalized, then it is rejected")
    void rejectsBelowMinimum() {
        // Given content one character short of MIN_LENGTH
        String content = "a".repeat(MessageContent.MIN_LENGTH - 1);

        // When normalized then rejected before reaching the pipeline
        assertThatThrownBy(() -> MessageContent.normalize(content))
                .isInstanceOf(InvalidCommentException.class);
    }

    @Test
    @DisplayName("Given exactly the minimum, when normalized, then it is kept intact")
    void acceptsExactMinimum() {
        // Given content of exactly MIN_LENGTH characters
        String content = "a".repeat(MessageContent.MIN_LENGTH);

        // When normalized
        MessageContent result = MessageContent.normalize(content);

        // Then accepted as-is, the boundary is inclusive
        assertThat(result.value()).isEqualTo(content);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("Given padding that only reaches the minimum with spaces, when normalized, then it is rejected")
    void paddingDoesNotCountTowardsMinimum() {
        // Given 24 real characters padded with spaces well above MIN_LENGTH
        String content = "     " + "a".repeat(MessageContent.MIN_LENGTH - 1) + "     ";

        // When normalized then rejected: the rule measures the trimmed text
        assertThatThrownBy(() -> MessageContent.normalize(content))
                .isInstanceOf(InvalidCommentException.class);
    }

    @Test
    @DisplayName("Given padded content at the minimum, when normalized, then it is accepted and trimmed")
    void acceptsPaddedMinimumTrimmed() {
        // Given MIN_LENGTH real characters surrounded by spaces
        String core = "a".repeat(MessageContent.MIN_LENGTH);

        // When normalized
        MessageContent result = MessageContent.normalize("  " + core + "  ");

        // Then accepted and trimmed
        assertThat(result.value()).isEqualTo(core);
    }

    @Test
    @DisplayName("Given emojis below the minimum, when normalized, then each emoji counts as one character")
    void emojiBelowMinimumIsRejected() {
        // Given 24 emojis: 48 UTF-16 units, but only 24 characters
        String content = EMOJI.repeat(MessageContent.MIN_LENGTH - 1);

        // When normalized then rejected, the surrogate pairs do not inflate the count
        assertThatThrownBy(() -> MessageContent.normalize(content))
                .isInstanceOf(InvalidCommentException.class);
    }

    @Test
    @DisplayName("Given emojis at the minimum, when normalized, then it is accepted")
    void emojiAtMinimumIsAccepted() {
        // Given exactly MIN_LENGTH emojis
        String content = EMOJI.repeat(MessageContent.MIN_LENGTH);

        // When normalized
        MessageContent result = MessageContent.normalize(content);

        // Then accepted intact
        assertThat(result.value()).isEqualTo(content);
        assertThat(result.truncated()).isFalse();
    }
}
