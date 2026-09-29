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
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;

/**
 * Unit tests for the LLM adapter with a mocked ChatClient (no network, no key).
 * Derivado de: plan 002 §3 (schema inválido → 1 reintento → throw; el listener
 * convierte el throw en LLM_FALLBACK, nunca 500).
 */
@DisplayName("AnalyzeMessageLlmAdapter")
class AnalyzeMessageLlmAdapterTest {

    private static final String VALID = """
            {"messageAuthor": "Hola, alguien sabe desplegar en OCI",
             "language": "ES", "sentiment": "NEUTRAL", "messageType": "DUDA",
             "topics": ["oci", "despliegue"], "relevance": 65,
             "channelPost": "FAQ", "titlePost": "Despliegue en OCI: error 401",
             "outputContentProcessed": "¿Cómo desplegar una API en OCI cuando aparece el error 401?",
             "hashtags": ["#OCI"], "cta": null}""";

    private ChatClient.ChatClientRequestSpec spec;
    private CallResponseSpec callSpec;
    private AnalyzeMessageLlmAdapter service;

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
        // Why a real sanitizer: these tests exercise the full wire-format path
        // (fences, preamble cut, retry) — mocking it would hide regressions.
        service = new AnalyzeMessageLlmAdapter(builder, new SystemPrompt(), new LlmOutputSanitizer());
    }

    @Test
    @DisplayName("Given valid JSON, when processed, then all 11 contract fields land")
    void parsesValidJson() {
        // Given the model answers the full 11-field contract
        when(callSpec.content()).thenReturn(VALID);

        // When processed
        ResponseModel response = service.processMessage(new RequestToLLM("hola oci?"));

        // Then metadata fields land
        assertThat(response.messageAuthor()).isEqualTo("Hola, alguien sabe desplegar en OCI");
        assertThat(response.language()).isEqualTo(Language.ES);
        assertThat(response.sentiment()).isEqualTo(Sentiment.NEUTRAL);
        assertThat(response.messageType()).isEqualTo(MessageType.DUDA);
        assertThat(response.topics()).containsExactly("oci", "despliegue");
        assertThat(response.relevance()).isEqualTo(65);
        // Then post fields land (the sanitizer must not mangle the object)
        assertThat(response.channelPost()).isEqualTo(Channels.FAQ);
        assertThat(response.titlePost()).isEqualTo("Despliegue en OCI: error 401");
        assertThat(response.outputContentProcessed()).isEqualTo("¿Cómo desplegar una API en OCI cuando aparece el error 401?");
        assertThat(response.hashtags()).containsExactly("#OCI");
        assertThat(response.cta()).isNull();
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
    @DisplayName("Given preamble around JSON, when processed, then the object is extracted")
    void extractsObjectFromPreamble() {
        // Given a chatty answer with preamble and epilogue (seen live with Mistral)
        when(callSpec.content()).thenReturn(
                "Claro, aquí tienes el JSON:\n" + VALID + "\nEspero que te sirva.");

        // When processed then parsed without retry
        assertThat(service.processMessage(new RequestToLLM("x")).relevance()).isEqualTo(65);
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
