package com.nocountry.simulation.communitylab.domain.entity;

import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

import java.util.List;

public record ResponseModel(
        String messageProcess,
        Language language,
        Sentiment sentiment,
        MessageType messageType,
        List<String> topics,
        int relevance
) {
    public static final int MAX_TOPICS = 5;

    public ResponseModel {
        // The LLM may omit topics or overflow them; the contract stays valid by construction.
        topics = topics == null ? List.of()
                : List.copyOf(topics.size() > MAX_TOPICS ? topics.subList(0, MAX_TOPICS) : topics);
        relevance = Math.min(100, Math.max(0, relevance));
    }

    /** Fallback object when the LLM fails - neutral, empty, zero. */
    public static ResponseModel fallback() {
        return new ResponseModel(null, null, Sentiment.NEUTRAL, MessageType.OTRO, List.of(), 0);
    }
}
