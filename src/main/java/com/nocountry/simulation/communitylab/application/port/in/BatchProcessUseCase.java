package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessRequest;
import com.nocountry.simulation.communitylab.application.dtos.batch.BatchProcessResponse;

public interface BatchProcessUseCase {

    BatchProcessResponse process(BatchProcessRequest request);
}
