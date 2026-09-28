package com.nocountry.simulation.communitylab.infrastructure.exception;

//Fail-fast on missing LLM configuration.
public class LlmNotConfiguredException extends RuntimeException {
    public LlmNotConfiguredException(String message) {
        super(message);
    }
}
