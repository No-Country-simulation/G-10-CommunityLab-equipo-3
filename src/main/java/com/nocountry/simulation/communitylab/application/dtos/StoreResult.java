package com.nocountry.simulation.communitylab.application.dtos;

public record StoreResult(
        boolean ok,
        boolean deduped
) {

    public static StoreResult failed(){
        return new StoreResult(false, false);
    }
}
