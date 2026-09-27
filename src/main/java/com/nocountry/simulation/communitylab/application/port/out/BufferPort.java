package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;

// Resolve test
public interface BufferPort {
    String getCurrentBatchId();
    void appendToBatch(EnrichedComment enrichedComment);
}
