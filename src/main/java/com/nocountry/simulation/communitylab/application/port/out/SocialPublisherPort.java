package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;

public interface SocialPublisherPort {

    PublishToXResponse publishToX(String text);

    boolean isConfigured();
}
