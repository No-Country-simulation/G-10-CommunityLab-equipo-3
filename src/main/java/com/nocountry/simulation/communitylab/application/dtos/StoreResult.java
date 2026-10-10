package com.nocountry.simulation.communitylab.application.dtos;

import java.time.Instant;

public record StoreResult(
        Status status,
        String parUrl,
        Instant expiresAt
) {
    public enum Status {
        STORED,
        ALREADY_STORED,
        FAILED
    }

    public StoreResult{
        if(status == null){
            throw new IllegalArgumentException("Status cannot be null");
        }
        if(status != Status.FAILED) {
            if(parUrl == null || expiresAt == null) {
                throw new IllegalArgumentException("parUrl and expiresAt cannot be null");
            }
        }
    }

    public boolean ok(){
        return status != Status.FAILED;
    }

    public boolean deduped(){
        return status == Status.ALREADY_STORED;
    }

    public static StoreResult failed(){
        return new StoreResult(Status.FAILED, null, null);
    }

    public static StoreResult stored(String parUrl, Instant expiresAt){
        return new StoreResult(Status.STORED, parUrl, expiresAt);
    }

    public static StoreResult alreadyStored(String parUrl, Instant expiresAt){
        return new StoreResult(Status.ALREADY_STORED, parUrl, expiresAt);
    }

    // Why: the PAR URL is a bearer secret; the default record toString would leak it into any log.
    @Override
    public String toString() {
        return "StoreResult{" +
                "parUrl='**** " + '\'' +
                ", status=" + status +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
