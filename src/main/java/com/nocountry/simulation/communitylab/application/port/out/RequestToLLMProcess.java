package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;

public interface RequestToLLMProcess {
    ResponseModel processMessage(RequestToLLM request);
}
