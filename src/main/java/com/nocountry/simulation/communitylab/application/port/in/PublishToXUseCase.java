package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXRequest;
import com.nocountry.simulation.communitylab.application.dtos.publish.PublishToXResponse;

public interface PublishToXUseCase {

    PublishToXResponse publish(PublishToXRequest request);
}
