package com.nocountry.simulation.communitylab.application.exception;

public class StorageMetricsUnavailableException extends RuntimeException {
    public StorageMetricsUnavailableException(String message) {
        super(message);
    }
    public StorageMetricsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
