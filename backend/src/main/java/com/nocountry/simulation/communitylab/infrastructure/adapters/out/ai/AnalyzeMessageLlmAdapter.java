package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.infrastructure.config.bucket.RateLimitAiConfig;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class AnalyzeMessageLlmAdapter implements RequestToLLMProcess {

    private final ChatClient chatClient;
    private final SystemPrompt systemPrompt;
    private final LlmOutputSanitizer sanitizer;
    // This temperature is used for the model for redaction post
    private static final double TEMPERATURE_MODEL = 0.6d;
    // Rate Limit
    private final RateLimitAiConfig bucket;
    // Why the semaphore is the real bound: @Async runs on virtual threads (unbounded), so this caps calls in flight;
    // a virtual thread parked on it costs nothing and never blocks the ingest thread (spec 010 RF-07).
    private final Semaphore semaphore;


    public AnalyzeMessageLlmAdapter(ChatClient.Builder chatClientBuilder,
                                    SystemPrompt systemPrompt,
                                    LlmOutputSanitizer sanitizer, RateLimitAiConfig bucket,
                                    @Value("${llm.max-concurrency:3}") int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalStateException("LLM_MAX_CONCURRENCY must be at least 1");
        }
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClientBuilder.build();
        this.sanitizer = sanitizer;
        this.bucket = bucket;
        this.semaphore = new Semaphore(maxConcurrency);
    }


    // Method Provisional -> Monitoring Model
    private static void logResponseMetadata(ChatClient.CallResponseSpec callResponse) {
        try {
            var response = callResponse.chatResponse();
            String finish = "?";
            if (response.getResult() != null && response.getResult().getMetadata() != null) {
                finish = String.valueOf(response.getResult().getMetadata().getFinishReason());
            }
            Object usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            log.debug("llm response metadata: finishReason={} usage={}", finish, usage);
        } catch (Exception e) {
            log.debug("llm response metadata unavailable: {}", e.getClass().getSimpleName());
        }
    }


    // Send message to the LLM
    private ResponseModel callOnce(RequestToLLM request, String systemText) {
        try{
            ChatClient.CallResponseSpec callResponse = chatClient.prompt()
                    .system(systemText)
                    .user(request.message())
                    .options(OpenAiChatOptions.builder()
                            .temperature(TEMPERATURE_MODEL)
                            .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                                    .build())
                            .maxTokens(4000)
                            //If not response in 15 seconds, throw exception
                            .timeout(Duration.ofSeconds(15)))
                    .call();
            logResponseMetadata(callResponse);
            return sanitizer.convert(callResponse.content());
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    // Call LLM method callOnce
    @Override
    public ResponseModel processMessage(RequestToLLM request) throws InterruptedException {
        String systemText = systemPrompt.systemPromptRedactPost() + "\n\n Responde exclusivamente con este SCHEMA\n" + sanitizer.schemaFormat();

        try {
            // First attempt
            return callLimited(request, systemText);
        } catch (Exception first) {
            // Why the type only: parse errors can quote fragments of the model output, derived from the user's message (Q3).
            log.warn("LLM intento 1/2 fallido: cause={}", first.getClass().getSimpleName());
            String repair = systemText + "\n\nPrevious output was invalid ("
                    + first.getMessage() + "). Respond with ONLY the JSON object.";
            return callLimited(request, repair);
        }
    }

    public ResponseModel callLimited (RequestToLLM request, String systemText) throws InterruptedException {
        // Why pre-log en debug: observabilidad del gate sin PII (solo conteos, nunca message/author).
        // En prod queda en debug para no spamear el pipeline async; warn solo al saturar/fallar.
        log.debug("LLM puerta de entrada, antes de pedir permiso: semaforos disponibles={} tokens disponibles={}",
                semaphore.availablePermits(), bucket.rateLimitAi().getAvailableTokens());
        semaphore.acquire(1);
        try {
            log.debug("LLM permiso concedido: semaforos libres={} tokens disponibles={}",
                    semaphore.availablePermits(), bucket.rateLimitAi().getAvailableTokens());
            bucket.rateLimitAi().asBlocking().consume(1);
            // If the probe is consumed, call the LLM method callOnce
            log.debug("LLM turno consumido, llamando al modelo: semaforos libres={} tokens restantes={}",
                    semaphore.availablePermits(), bucket.rateLimitAi().getAvailableTokens());
            return callOnce(request, systemText);
        } finally {
            // Release semaphore
            semaphore.release(1);
            log.debug("LLM permiso liberado: semaforos disponibles={}", semaphore.availablePermits());
        }
    }
}
