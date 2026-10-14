package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.dtos.RevisionResult;

public interface RevisePostUseCase {
    RevisionResult revise(RevisePostCommand command);
}
