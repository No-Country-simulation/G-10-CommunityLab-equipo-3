package com.nocountry.simulation.communitylab.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Unit tests for the pure relevance rules (no Spring).
 * Mirrors {@code spec 002 RF-01/RF-03}: Given/When/Then.
 */
@DisplayName("RelevancePolicy")
class RelevancePolicyTest {

    @Test
    @DisplayName("Given positive LOGRO with low proposal, when scored, then floor 70 applies")
    void floorsPositiveLogro() {
        // Given a positive achievement with a low LLM proposal
        int score = RelevancePolicy.score(
                MessageType.LOGRO, Sentiment.POSITIVO, 60, false, 50);

        // Then the RF-01 floor applies
        assertThat(score).isEqualTo(70);
    }

    @Test
    @DisplayName("Given similar LOGRO and DUDA, when scored, then LOGRO outranks DUDA")
    void logroOutranksDuda() {
        // Given similar texts with high proposals
        int logro = RelevancePolicy.score(
                MessageType.LOGRO, Sentiment.POSITIVO, 60, false, 50);
        int duda = RelevancePolicy.score(
                MessageType.DUDA, Sentiment.NEUTRAL, 60, false, 90);

        // Then the positive achievement outranks the question
        assertThat(logro).isGreaterThan(duda);
    }

    @Test
    @DisplayName("Given text under 15 chars, when scored, then it is irrelevant")
    void rejectsShortText() {
        // Given a 2-char text with a top LLM proposal
        int score = RelevancePolicy.score(
                MessageType.LOGRO, Sentiment.POSITIVO, 2, false, 95);

        // Then length beats the proposal
        assertThat(score).isZero();
    }

    @Test
    @DisplayName("Given truncated comment, when scored, then no penalty applies")
    void truncatedHasNoPenalty() {
        // Given the same DUDA truncated or not
        int intact = RelevancePolicy.score(
                MessageType.DUDA, Sentiment.NEUTRAL, 60, false, 60);
        int truncated = RelevancePolicy.score(
                MessageType.DUDA, Sentiment.NEUTRAL, 60, true, 60);

        // Then the cut does not lower the score
        assertThat(truncated).isEqualTo(intact);
    }

    @Test
    @DisplayName("Given out-of-range proposal, when scored, then it is clamped 0..100")
    void clampsProposal() {
        // Given proposals outside the valid range
        assertThat(RelevancePolicy.score(
                        MessageType.OTRO, Sentiment.NEUTRAL, 60, false, 150))
                .isEqualTo(100);
        assertThat(RelevancePolicy.score(
                        MessageType.OTRO, Sentiment.NEUTRAL, 60, false, -5))
                .isZero();
    }

    @Test
    @DisplayName("Given DUDA with top proposal, when scored, then cap 69 applies")
    void capsDuda() {
        // Given a question with a top LLM proposal
        int score = RelevancePolicy.score(
                MessageType.DUDA, Sentiment.NEUTRAL, 60, false, 90);

        // Then the ordering cap applies
        assertThat(score).isEqualTo(69);
    }

    @Test
    @DisplayName("Given null type, when scored, then it behaves as OTRO")
    void nullTypeIsOtro() {
        // Given a missing type with a mid proposal
        int score = RelevancePolicy.score(null, Sentiment.NEUTRAL, 60, false, 55);

        // Then the proposal passes through untouched
        assertThat(score).isEqualTo(55);
    }
}
