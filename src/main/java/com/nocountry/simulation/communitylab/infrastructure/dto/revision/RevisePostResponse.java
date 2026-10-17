package com.nocountry.simulation.communitylab.infrastructure.dto.revision;

import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;

public record RevisePostResponse(
        String batchId,
        String messageId,
        int versionMessage,
        ApprovalStatus approved,
        boolean revised
) {
}
