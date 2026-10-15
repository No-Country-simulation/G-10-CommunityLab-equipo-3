package com.nocountry.simulation.communitylab.application.dtos;

public record RequestCounts (
        long total,
        long get,
        long put,
        long list,
        long head,
        long delete,
        long clientErrors
) {
    public static RequestCounts zero (){
        return new RequestCounts(0, 0, 0, 0, 0, 0, 0);
    }
}
