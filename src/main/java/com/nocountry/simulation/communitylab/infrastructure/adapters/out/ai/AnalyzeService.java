package com.nocountry.simulation.communitylab.infrastructure.adapters.out.ai;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.infrastructure.dto.ai.SystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Service
@Slf4j
public class AnalyzeService implements RequestToLLMProcess {

    private final ChatClient chatClient;
    private final SystemPrompt systemPrompt;
    private final BeanOutputConverter<ResponseModel> converter;

    public AnalyzeService(ChatClient.Builder chatClientBuilder,
                          SystemPrompt systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClientBuilder.build();
        this.converter = new BeanOutputConverter<>(ResponseModel.class, lenientMapper());
    }

    // Mapper for response of the model.
    private static JsonMapper lenientMapper() {
        return JsonMapper.builder()
                .addModules(JacksonUtils.instantiateAvailableModules())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }

    // Intern cleaning for ```
    private static String stripOuterFences(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int nl = text.indexOf('\n');
        String firstLine = nl < 0 ? text : text.substring(0, nl);
        String rest = nl < 0 ? "" : text.substring(nl + 1);
        String first = firstLine.replaceFirst("^```[a-zA-Z]*", "");
        String combined = rest.isEmpty() ? first : (first + "\n" + rest);
        String stripped = combined.strip();
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3).stripTrailing();
        }
        return stripped;
    }


    // Send message to the LLM
    private ResponseModel callOnce(RequestToLLM request, String systemText) {
        String raw = chatClient.prompt()
                .system(systemText)
                .user(request.message())
                .options(OpenAiChatOptions.builder()
                        .temperature(0.1)
                        .timeout(Duration.ofSeconds(15)))
                .call()
                .content();
        return converter.convert(stripOuterFences(raw));
    }

    // Call LLM method callOnce
    @Override
    public ResponseModel processMessage(RequestToLLM request) {

        String systemText = systemPrompt.systemPromptAnalyzeMessage() + "\n\n Responde exclusivamente con este SCHEMA\n" + converter.getFormat();

        try{
            ResponseModel response = callOnce(request, systemText);
            return response;
        } catch (Exception e) {
            log.error("Error al procesar el mensaje: {}", e.getMessage());
        }

        try{
            ResponseModel response = callOnce(request, systemText);
            return response;
        } catch (Exception e) {
            log.error("Error al procesar el mensaje: {}", e.getMessage());
            throw e;
        }
    }
}
