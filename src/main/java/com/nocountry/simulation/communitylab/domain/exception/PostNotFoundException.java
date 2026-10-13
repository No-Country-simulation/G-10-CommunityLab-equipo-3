package com.nocountry.simulation.communitylab.domain.exception;

// The batch exists but has no record with that messageId (spec 007 RF-02 -> 404).
public class PostNotFoundException extends RuntimeException {
    public PostNotFoundException(String message) {
        super(message);
    }
}
