package com.nocountry.simulation.communitylab.application.dtos;

// What the revision use case hands back to the web adapter; changed=false means nothing was written (no-op).
public record RevisionResult(
        String batchId,
        String messageId,
        int versionMessage,
        boolean approved,
        boolean changed
) {
}
