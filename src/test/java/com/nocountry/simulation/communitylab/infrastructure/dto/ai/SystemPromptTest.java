package com.nocountry.simulation.communitylab.infrastructure.dto.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural invariants of the redact-post system prompt (no Spring, no LLM).
 * Mirrors spec 003 RF-04 + enmienda FAQ-pregunta 2026-09-28: Dado/Cuando/Entonces.
 *
 * <p>Scope: the prompt text as a contract — doubts/complaints always route to
 * FAQ, the model never answers, FAQ carries a mandatory title and no CTA.
 * Wording may evolve; the invariants below may not silently disappear.
 */
@DisplayName("SystemPrompt")
class SystemPromptTest {

    private final SystemPrompt prompt = new SystemPrompt();

    @Test
    @DisplayName("Given the redact prompt, when read, then doubts and complaints route to FAQ only")
    void routesDoubtsAndComplaintsToFaq() {
        // Given the live redact-post prompt
        String text = prompt.systemPromptRedactPost();

        // Then doubts, complaints and questions are FAQ-only, never X/LinkedIn
        assertThat(text).contains("QUEJA");
        assertThat(text).contains("FAQ siempre");
        assertThat(text).contains("PROHIBIDO asignar X o LinkedIn a preguntas o quejas");
    }

    @Test
    @DisplayName("Given the redact prompt, when read, then FAQ forbids answering and requires title without CTA")
    void faqCuratesWithoutAnswering() {
        // Given the live redact-post prompt
        String text = prompt.systemPromptRedactPost();

        // Then the scope guard (never answer) and the FAQ shape hold
        assertThat(text).contains("PROHIBIDO responder, solucionar, aconsejar o diagnosticar");
        assertThat(text).contains("obligatorio, título de la duda o queja");
        assertThat(text).contains("siempre null");
    }
}
