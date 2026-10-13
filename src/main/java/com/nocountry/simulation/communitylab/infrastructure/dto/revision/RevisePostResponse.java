package com.nocountry.simulation.communitylab.infrastructure.dto.revision;

public record RevisePostResponse(
        String batchId,
        String messageId,
        int versionMessage,
        boolean approved
) {
}
