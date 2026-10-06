package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocountry.simulation.communitylab.infrastructure.config.bucket.RateLimitAiConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BlockingBucket;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import java.time.Duration;

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
    private RateLimitAiConfig rateLimitCfg;
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
        // Why a generous real bucket: the prod Bucket (3/60s) would block the
        // suite on the 4th consume (asBlocking). Here we only need the gate
        // to let through; rate-limit exhaustion is covered with mocks below.
        // Why a real sanitizer: these tests exercise the full wire-format path
        // (fences, preamble cut, retry) — mocking it would hide regressions.
        rateLimitCfg = mock(RateLimitAiConfig.class);
        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(100)
                        .refillGreedy(100, Duration.ofSeconds(1))
                        .build())
                .build();
        when(rateLimitCfg.rateLimitAi()).thenReturn(bucket);
        service = new AnalyzeMessageLlmAdapter(builder, new SystemPrompt(), new LlmOutputSanitizer(), rateLimitCfg);
    }

    private AnalyzeMessageLlmAdapter serviceWithBucket(Bucket bucket) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec freshSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(freshSpec);
        when(freshSpec.system(anyString())).thenReturn(freshSpec);
        when(freshSpec.user(anyString())).thenReturn(freshSpec);
        when(freshSpec.options((org.springframework.ai.chat.prompt.ChatOptions.Builder) any()))
                .thenReturn(freshSpec);
        when(freshSpec.call()).thenReturn(callSpec);
        RateLimitAiConfig cfg = mock(RateLimitAiConfig.class);
        when(cfg.rateLimitAi()).thenReturn(bucket);
        return new AnalyzeMessageLlmAdapter(builder, new SystemPrompt(), new LlmOutputSanitizer(), cfg);
    }

    @Test
    @DisplayName("Given valid JSON, when processed, then all 11 contract fields land")
    void parsesValidJson() throws InterruptedException {
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
    void stripsOuterFences() throws InterruptedException {
        // Given markdown-wrapped answers (both variants seen live)
        when(callSpec.content()).thenReturn("```json\n" + VALID + "\n```");
        assertThat(service.processMessage(new RequestToLLM("a")).relevance()).isEqualTo(65);

        when(callSpec.content()).thenReturn("```" + VALID + "\n```");
        assertThat(service.processMessage(new RequestToLLM("b")).relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given preamble around JSON, when processed, then the object is extracted")
    void extractsObjectFromPreamble() throws InterruptedException {
        // Given a chatty answer with preamble and epilogue (seen live with Mistral)
        when(callSpec.content()).thenReturn(
                "Claro, aquí tienes el JSON:\n" + VALID + "\nEspero que te sirva.");

        // When processed then parsed without retry
        assertThat(service.processMessage(new RequestToLLM("x")).relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given garbage first, when processed, then one retry saves it")
    void retriesOnce() throws InterruptedException {
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

    @Test
    @DisplayName("Given a double failure, when processed again with valid JSON, then the permit was released")
    void semaphoreReleasedAfterFailure() throws InterruptedException {
        // Given a call that exhausts the 1 retry and throws
        when(callSpec.content()).thenReturn("language");
        assertThatThrownBy(() -> service.processMessage(new RequestToLLM("x")))
                .isInstanceOf(RuntimeException.class);

        // When processed again with valid JSON then it still goes through
        // (proves the Semaphore permit was released in finally, no leak)
        when(callSpec.content()).thenReturn(VALID);
        assertThat(service.processMessage(new RequestToLLM("y")).relevance()).isEqualTo(65);
    }

    @Test
    @DisplayName("Given an interrupted rate-limit gate, when processed, then InterruptedException propagates")
    void interruptedRateLimitThenThrows() throws Exception {
        // Given a Bucket4j gate that is interrupted while waiting for a token
        // Why doThrow: BlockingBucket.consume(long) returns void, when() cannot stub void.
        Bucket limited = mock(Bucket.class);
        BlockingBucket blocking = mock(BlockingBucket.class);
        when(limited.asBlocking()).thenReturn(blocking);
        doThrow(new InterruptedException("rate-limit interrupted")).when(blocking).consume(1);
        AnalyzeMessageLlmAdapter interruptedService = serviceWithBucket(limited);

        // When processed then the interrupt surfaces (listener maps it to LLM_FALLBACK)
        // Note: prod retries once, so consume is hit twice before propagating.
        assertThatThrownBy(() -> interruptedService.processMessage(new RequestToLLM("x")))
                .isInstanceOf(InterruptedException.class);
    }
}
