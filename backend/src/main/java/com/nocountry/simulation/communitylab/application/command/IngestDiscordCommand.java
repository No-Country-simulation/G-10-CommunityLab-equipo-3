package com.nocountry.simulation.communitylab.application.command;

import java.time.Instant;

public record IngestDiscordCommand(
        String messageId,
        String channelId,
        String authorId,
        String authorName,
        String content,
        Instant sentTime)
{
}
