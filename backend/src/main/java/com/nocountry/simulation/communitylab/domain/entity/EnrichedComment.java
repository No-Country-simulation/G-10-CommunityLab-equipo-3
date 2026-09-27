package com.nocountry.simulation.communitylab.domain.entity;

import java.time.Instant;
import java.util.List;

import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

// Structure for buffering in Redis
public record EnrichedComment(
        // Now don't utilize batchIdLote but for storage in Redis it's necessary
        String batchidLote,
        String messageId,
        String channelId,
        String authorId,
        String authorName,
        String contentProcessed,
        Sentiment sentiment,
        Language language,
        MessageType messageType,
        List<String> topics,
        int relevance,
        String flag,
        String promptVersion,
        Instant sentTime,
        Source source) {

    public static final String LLM_FALLBACK = "LLM_FALLBACK";

    // Prompt version that produced the analysis.
    public static final String PROMPT_VERSION = "v1";

    public EnrichedComment {
        topics = topics == null ? List.of() : List.copyOf(topics);
        relevance = Math.min(100, Math.max(0, relevance));
    }
}
