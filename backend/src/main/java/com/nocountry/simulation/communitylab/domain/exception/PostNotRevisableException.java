package com.nocountry.simulation.communitylab.domain.exception;

// An LLM_FALLBACK record has no post, so it can't be approved or edited (spec 007 RF-03 -> 422).
public class PostNotRevisableException extends RuntimeException {
    public PostNotRevisableException(String message) {
        super(message);
    }
}
