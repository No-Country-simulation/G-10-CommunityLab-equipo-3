package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;

public interface RevisePostUseCase {
    void revise(RevisePostCommand command);
}
