package com.nocountry.simulation.communitylab.infrastructure.config.ai;

import com.nocountry.simulation.communitylab.infrastructure.exception.LlmNotConfiguredException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FailEnvModelConfiguration {

    private final String apiKey;
    private final String model;
    private final String baseUrl;


    public FailEnvModelConfiguration(
            @Value("${API_KEY_LLM_MISTRAL_DEV}") String apiKey,
            @Value("${MODEL_MISTRAL}") String model,
            @Value("${BASE_URL_MODEL_AI}") String baseUrl
            ){
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    public void validateStatusLLM(){
        if (apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()
                || baseUrl == null || baseUrl.isBlank()) {
            throw new LlmNotConfiguredException(
                    "LLM_NOT_CONFIGURED: API_KEY_LLM_MISTRAL_DEV/MODEL_MISTRAL/BASE_URL_MODEL_AI required");
        }
    }
}
