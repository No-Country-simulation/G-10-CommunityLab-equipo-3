package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
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
        Boolean revised,
        ApprovalStatus approved,
        Integer versionMessage,
        Boolean reject,
        Instant publishedAt) {

    // Why one place: the live event and the history snapshot must never send different shapes of the same post.
    public static ResponseClient from(EnrichedComment comment) {
        return new ResponseClient(
                comment.authorName(),
                comment.messageAuthor(),
                comment.messageId(),
                comment.messageBatchId(),
                comment.sentiment(),
                comment.language(),
                comment.messageType(),
                comment.topics(),
                comment.relevance(),
                comment.flag(),
                comment.sentTime(),
                comment.source(),
                comment.channelPost(),
                comment.titlePost(),
                comment.outputContentProcessed(),
                comment.hashtags(),
                comment.cta(),
                comment.revised(),
                comment.approved(),
                comment.versionMessage(),
                comment.reject(),
                comment.publishedAt()
        );
    }
}
