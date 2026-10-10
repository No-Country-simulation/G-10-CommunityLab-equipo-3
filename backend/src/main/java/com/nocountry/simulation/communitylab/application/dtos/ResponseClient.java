package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

import java.time.Instant;
import java.util.List;

public record ResponseClient(
        String authorName,
        String messageAuthor,
        String messageId,
        String messageBatchId,
        Sentiment sentiment,
        Language language,
        MessageType messageType,
        List<String> topics,
        int relevance,
        String flag,
        Instant sentTime,
        Source source,
        Channels channelPost,
        String titlePost,
        String outputContentProcessed,
        List<String> hashtags,
        String cta,
        Boolean approved,
        Integer versionMessage) {
}
