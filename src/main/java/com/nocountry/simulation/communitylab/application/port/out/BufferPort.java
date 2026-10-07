package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BufferPort {
    String getCurrentBatchId(Source source);
    long appendToBatch(EnrichedComment enrichedComment);
    long countMessages(Source source);
    Optional<String> sealCurrentBatch (Source source);
    Set<String> pendingBatches (Source source);
    // For build AssetPackage
    List<EnrichedComment> readSealed(Source source, String batchId);
    void discardSealed (Source source, String batchId);

}
