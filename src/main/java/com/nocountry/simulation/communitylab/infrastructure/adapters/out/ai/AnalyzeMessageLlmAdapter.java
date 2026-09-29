package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
public class AnalyzeMessageLlmAdapter implements RequestToLLMProcess {

    private final ChatClient chatClient;
    private final SystemPrompt systemPrompt;
    private final LlmOutputSanitizer sanitizer;
    // This temperature is used for the model for redaction post
    private static final double TEMPERATURE_MODEL = 0.6d;


    public AnalyzeMessageLlmAdapter(ChatClient.Builder chatClientBuilder,
                                    SystemPrompt systemPrompt,
                                    LlmOutputSanitizer sanitizer) {
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClientBuilder.build();
        this.sanitizer = sanitizer;
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
    }

    // Call LLM method callOnce
    @Override
    public ResponseModel processMessage(RequestToLLM request) {

        String systemText = systemPrompt.systemPromptRedactPost() + "\n\n Responde exclusivamente con este SCHEMA\n" + sanitizer.schemaFormat();

        try{
            ResponseModel response = callOnce(request, systemText);
            return response;
        } catch (Exception first) {
            log.warn("llm attempt 1/2 failed: {}", first.getMessage());

            String repair = systemText + "\n\nPrevious output was invalid ("
                    + first.getMessage() + "). Respond with ONLY the JSON object.";
            try {
                return callOnce(request, repair);
            } catch (Exception second) {
                log.warn("llm attempt 2/2 failed: {}", second.getMessage());
                throw second;
            }
        }
    }
}
