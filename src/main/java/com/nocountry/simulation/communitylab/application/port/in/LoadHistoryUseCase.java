package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.domain.enums.Source;

public interface LoadHistoryUseCase {
    History load(Source source);
}
