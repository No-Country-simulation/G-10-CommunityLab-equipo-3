package com.nocountry.simulation.communitylab.application.command;

import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.time.Instant;

public record IngestTelegramCommand(
        Integer messageId,
        Long channelId,
        Long authorId,
        String authorName,
        String content,
        Instant sentTime) {
}
