package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.time.Instant;

public record PackageCompleted(
        String batchId,
        Source source,
        int assetsCount,
        String parUrlBase,
        Instant expiresAt
) {

    // Why: parUrlBase is the PAR bearer secret; the default record toString would leak it into any log.
    @Override
    public String toString() {
        return "PackageCompleted{" +
                "batchId='" + batchId + '\'' +
                ", source=" + source +
                ", assetsCount=" + assetsCount +
                ", parUrlBase='****'" +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
