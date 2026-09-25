package com.nocountry.simulation.communitylab.application.dtos;

import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.time.Instant;

public record ChannelMessage(
        String messageId,
        String channelId,
        String authorId,
        String authorName,
        String content,
        Instant sentTime,
        boolean truncated,
        Source source) {

    public static ChannelMessage from(Comment comment) {
        return new ChannelMessage(
                comment.messageId(),
                comment.channelId(),
                comment.authorId(),
                comment.authorName(),
                comment.content(),
                comment.sentTime(),
                comment.truncated(),
                comment.source());
    }
}
