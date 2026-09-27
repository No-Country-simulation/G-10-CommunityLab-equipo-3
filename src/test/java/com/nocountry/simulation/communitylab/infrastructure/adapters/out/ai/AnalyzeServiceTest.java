package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;

/**
 * Unit tests for the LLM adapter with a mocked ChatClient (no network, no key).
 * Derivado de: plan 002 §3 (schema inválido → 1 reintento → throw; el listener
 * convierte el throw en LLM_FALLBACK, nunca 500).
 */
@DisplayName("AnalyzeService")
class AnalyzeServiceTest {

    private static final String VALID = """
            {"messageProcess": "Hola, alguien sabe desplegar en OCI",
             "language": "ES", "sentiment": "NEUTRAL", "messageType": "DUDA",
             "topics": ["oci", "despliegue"], "relevance": 65}""";

    private ChatClient.ChatClientRequestSpec spec;
    private CallResponseSpec callSpec;
    private AnalyzeService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options((org.springframework.ai.chat.prompt.ChatOptions.Builder) any()))
                .thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        service = new AnalyzeService(builder, new SystemPrompt());
    }

    @Test
    @DisplayName("Given valid JSON, when processed, then it parses without retry")
    void parsesValidJson() {
        // Given the model answers clean JSON
        when(callSpec.content()).thenReturn(VALID);

        // When processed
        ResponseModel response = service.processMessage(new RequestToLLM("hola oci?"));

        // Then all six fields land
        assertThat(response.messageType()).isEqualTo(MessageType.DUDA);
        assertThat(response.sentiment()).isEqualTo(Sentiment.NEUTRAL);
        assertThat(response.language()).isEqualTo(Language.ES);
        assertThat(response.topics()).containsExactly("oci", "despliegue");
        assertThat(response.relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given fenced response, when processed, then outer fences are stripped")
    void stripsOuterFences() {
        // Given markdown-wrapped answers (both variants seen live)
        when(callSpec.content()).thenReturn("```json\n" + VALID + "\n```");
        assertThat(service.processMessage(new RequestToLLM("a")).relevance()).isEqualTo(65);

        when(callSpec.content()).thenReturn("```" + VALID + "\n```");
        assertThat(service.processMessage(new RequestToLLM("b")).relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given garbage first, when processed, then one retry saves it")
    void retriesOnce() {
        // Given failure then success (retry has value, seen live)
        when(callSpec.content()).thenReturn("language").thenReturn(VALID);

        // When processed then recovered without throw
        assertThat(service.processMessage(new RequestToLLM("x")).relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given garbage twice, when processed, then it throws for fallback")
    void throwsAfterSecondFailure() {
        // Given persistent garbage
        when(callSpec.content()).thenReturn("language");

        // When processed then throws (listener maps to LLM_FALLBACK, never 500)
        assertThatThrownBy(() -> service.processMessage(new RequestToLLM("x")))
                .isInstanceOf(RuntimeException.class);
    }
}
