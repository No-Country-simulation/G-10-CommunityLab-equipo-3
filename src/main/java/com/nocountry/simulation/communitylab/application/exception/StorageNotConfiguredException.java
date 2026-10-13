package com.nocountry.simulation.communitylab.application.exception;

// OCI_AUTH_MODE=none: there is no store to read or rewrite (spec 007 RF-09 -> 503).
public class StorageNotConfiguredException extends RuntimeException {
    public StorageNotConfiguredException(String message) {
        super(message);
    }
}
