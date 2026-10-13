package com.nocountry.simulation.communitylab.domain.exception;

// The user edited a stale copy of the post, or the package kept changing after the retries (spec 007 RF-06 -> 409).
public class PostVersionConflictException extends RuntimeException {
    public PostVersionConflictException(String message) {
        super(message);
    }
}
