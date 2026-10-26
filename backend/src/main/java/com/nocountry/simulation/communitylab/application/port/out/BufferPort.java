package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.util.Collection;
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
    // Several sealed batches in one read (SSE history); a missing or unreadable batch contributes nothing
    List<EnrichedComment> readSealedAll(Source source, Collection<String> batchIds);
    void discardSealed (Source source, String batchId);
    List<EnrichedComment> readOpen(Source source);

}
