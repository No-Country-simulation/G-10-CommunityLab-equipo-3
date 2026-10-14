package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;

public interface EventPublishPost {
    ResponseClient publish(ResponseClient post);

    void publishRevision(MessageRevised messageRevised);
}
