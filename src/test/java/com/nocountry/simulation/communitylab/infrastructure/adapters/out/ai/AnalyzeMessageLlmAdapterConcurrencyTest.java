package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.infrastructure.config.bucket.RateLimitAiConfig;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * LLM concurrency bound: with {@code @Async} on virtual threads (unbounded), the semaphore is the only cap on calls in
 * flight. ChatClient mocked at the boundary; the slow call is simulated so overlap is observable.
 *
 * <p>Derivado de: spec 010 RF-07 (máximo {@code LLM_MAX_CONCURRENCY} llamadas al LLM a la vez, configurable,
 * mínimo 1) + plan 010 §C.
 */
@DisplayName("AnalyzeMessageLlmAdapter (concurrency)")
class AnalyzeMessageLlmAdapterConcurrencyTest {

    private static final String VALID = """
            {"messageAuthor": "Hola, alguien sabe desplegar en OCI",
             "language": "ES", "sentiment": "NEUTRAL", "messageType": "DUDA",
             "topics": ["oci", "despliegue"], "relevance": 65,
             "channelPost": "FAQ", "titlePost": "Despliegue en OCI: error 401",
             "outputContentProcessed": "¿Cómo desplegar una API en OCI cuando aparece el error 401?",
             "hashtags": ["#OCI"], "cta": null}""";
    private static final int CALLERS = 6;

    private ChatClient.Builder builder;
    private CallResponseSpec callSpec;
    private RateLimitAiConfig rateLimitCfg;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options((org.springframework.ai.chat.prompt.ChatOptions.Builder) any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);

        // Why a generous bucket: only the semaphore may limit here, never the rate limit
        rateLimitCfg = mock(RateLimitAiConfig.class);
        when(rateLimitCfg.rateLimitAi()).thenReturn(Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(100).refillGreedy(100, Duration.ofSeconds(1)).build())
                .build());

        // Each simulated call stays open for a while and records how many overlap
        when(callSpec.content()).thenAnswer(invocation -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            Thread.sleep(40);
            inFlight.decrementAndGet();
            return VALID;
        });
    }

    @Test
    @DisplayName("Dado un límite de 2, cuando 6 virtual threads llaman a la vez, entonces nunca hay más de 2 llamadas en curso y todas terminan")
    void neverMoreCallsInFlightThanTheLimit() throws Exception {
        // Dado LLM_MAX_CONCURRENCY=2
        AnalyzeMessageLlmAdapter adapter = adapter(2);

        // Cuando 6 mensajes llegan juntos, cada uno en su virtual thread (como @Async)
        int completed = runConcurrently(adapter);

        // Entonces el proveedor ve como mucho 2 a la vez, y ninguno se pierde
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(2);
        assertThat(completed).isEqualTo(CALLERS);
    }

    @Test
    @DisplayName("Dado un límite de 1, cuando varios llaman a la vez, entonces las llamadas van de una en una")
    void limitOfOneSerializesCalls() throws Exception {
        // Dado LLM_MAX_CONCURRENCY=1
        AnalyzeMessageLlmAdapter adapter = adapter(1);

        // Cuando 6 llaman a la vez / Entonces nunca se solapan
        assertThat(runConcurrently(adapter)).isEqualTo(CALLERS);
        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un límite de 0 o negativo, cuando se construye, entonces falla al arrancar en vez de bloquear todo el pipeline")
    void nonPositiveLimitFailsFast() {
        assertThatThrownBy(() -> adapter(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_MAX_CONCURRENCY");
        assertThatThrownBy(() -> adapter(-1)).isInstanceOf(IllegalStateException.class);
    }

    private AnalyzeMessageLlmAdapter adapter(int maxConcurrency) {
        return new AnalyzeMessageLlmAdapter(builder, new SystemPrompt(), new LlmOutputSanitizer(), rateLimitCfg,
                maxConcurrency);
    }

    private int runConcurrently(AnalyzeMessageLlmAdapter adapter) throws Exception {
        List<Future<?>> calls = new ArrayList<>();
        try (var virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLERS; i++) {
                calls.add(virtualThreads.submit(() -> adapter.processMessage(new RequestToLLM("mensaje de prueba"))));
            }
        }
        int completed = 0;
        for (Future<?> call : calls) {
            assertThat(call.get()).isNotNull();
            completed++;
        }
        return completed;
    }
}
