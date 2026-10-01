package com.nocountry.simulation.communitylab.infrastructure.exception;

public class TelegramTokenMissingException extends RuntimeException {
    public TelegramTokenMissingException(String message) {
        super(message);
    }
}
