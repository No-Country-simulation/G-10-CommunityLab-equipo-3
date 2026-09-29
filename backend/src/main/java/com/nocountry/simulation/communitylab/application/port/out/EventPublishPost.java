package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;

public interface EventPublishPost {
    ResponseClient publish(ResponseClient post);
}
