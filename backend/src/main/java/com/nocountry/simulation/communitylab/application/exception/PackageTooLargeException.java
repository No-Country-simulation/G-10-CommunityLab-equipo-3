package com.nocountry.simulation.communitylab.application.exception;

// A stored or revised package reaches the 1MB budget, so it is neither read nor rewritten (spec 007 RNF-01 -> 413).
public class PackageTooLargeException extends RuntimeException {
    public PackageTooLargeException(String message) {
        super(message);
    }
}
