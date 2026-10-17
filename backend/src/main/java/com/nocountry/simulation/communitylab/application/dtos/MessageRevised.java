package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Source;

// SSE notice after a saved revision. Why no content: the dashboard reads the post through the PAR (spec 007 RF-08).
public record MessageRevised(
        Source source,
        String batchId,
        String messageId,
        int versionMessage,
        ApprovalStatus approved,
        boolean revised
) {
}
