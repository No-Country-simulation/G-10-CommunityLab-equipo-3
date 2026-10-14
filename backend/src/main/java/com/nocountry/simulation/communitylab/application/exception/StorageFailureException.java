package com.nocountry.simulation.communitylab.application.exception;

// OCI failed with something other than 404/412; provider details stay in the logs (spec 007 RF-09 -> 502).
public class StorageFailureException extends RuntimeException {
    public StorageFailureException(String message) {
        super(message);
    }
}
