package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;

// What the revision use case hands back to the web adapter; changed=false means nothing was written (no-op).
public record RevisionResult(
        String batchId,
        String messageId,
        int versionMessage,
        ApprovalStatus approved,
        boolean revised,
        boolean changed
) {
}
