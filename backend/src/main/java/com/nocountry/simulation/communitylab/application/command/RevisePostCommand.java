package com.nocountry.simulation.communitylab.application.command;

import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.Source;

public record RevisePostCommand(
        Source source,
        String batchId,
        String messageId,
        int expectedVersion,
        PostChange change
) {
}
