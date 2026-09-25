package com.nocountry.simulation.communitylab.domain.entity;

import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import com.nocountry.simulation.communitylab.domain.vo.MessageContent;

import java.time.Instant;

/**
 * Normalized inbound message, ready for AI analysis (spec 002).
 * No batchId (batching lives in a later lot container), no message type
 * (classification belongs to EnrichedComment in 002).
 */
public record Comment(
        String messageId,
        String channelId,
        String authorId,
        String authorName,
        String content,
        Instant sentTime,
        boolean truncated,
        Source source) {

    public Comment {
        if (messageId == null || messageId.isBlank()) {
            throw new InvalidCommentException("messageId is required");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new InvalidCommentException("authorId is required");
        }
        if (content == null) {
            throw new InvalidCommentException("content is required");
        }
        if (sentTime == null) {
            throw new InvalidCommentException("sentTime is required");
        }
        if (source == null) {
            throw new InvalidCommentException("source is required");
        }
    }

    public static Comment create(
            String messageId,
            String channelId,
            String authorId,
            String authorName,
            String content,
            Instant sentTime,
            Source source) {
        MessageContent normalized = MessageContent.normalize(content);
        return new Comment(
                messageId,
                channelId,
                authorId,
                authorName,
                normalized.value(),
                sentTime,
                normalized.truncated(),
                source);
    }
}
