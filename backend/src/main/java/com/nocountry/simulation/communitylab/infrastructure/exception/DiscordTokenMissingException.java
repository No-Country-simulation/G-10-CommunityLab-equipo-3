package com.nocountry.simulation.communitylab.infrastructure.exception;

/**
 * Fail-fast on missing Discord configuration (fundamental provider, plan 001 §4).
 */
public class DiscordTokenMissingException extends RuntimeException {
    public DiscordTokenMissingException(String message) {
        super(message);
    }
}
