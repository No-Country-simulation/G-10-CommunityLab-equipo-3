package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;

public interface EventPublishPost {
    EnrichedComment publish(EnrichedComment post);
}
