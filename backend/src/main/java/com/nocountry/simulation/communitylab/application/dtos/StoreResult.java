package com.nocountry.simulation.communitylab.application.dtos;

public record StoreResult(
        boolean ok,
        boolean deduped
) {

    public static StoreResult failed(){
        return new StoreResult(false, false);
    }

    public static StoreResult stored(){
        return new StoreResult(true, false);
    }

    public static StoreResult alreadyStored(){
        return new StoreResult(true, true);
    }
}
