package com.nocountry.simulation.communitylab.domain.entity;

import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;

import java.time.Instant;
import java.util.List;

public record PostChange (
        Boolean revised,
        ApprovalStatus approved,
        List<String> topics,
        Channels channelPost,
        String titlePost,
        String outputContentProcessed,
        List<String> hashtags,
        String cta,
        Instant publishedAt
){
    public PostChange {
        topics = topics == null ? null : List.copyOf(topics);
        hashtags = hashtags == null ? null : List.copyOf(hashtags);
    }
}
