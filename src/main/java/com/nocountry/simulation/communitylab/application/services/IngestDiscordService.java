package com.nocountry.simulation.communitylab.application.services;

import java.util.Optional;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseDiscord;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IngestDiscordService implements IngestUseCaseDiscord {

    @Override
    public Optional<ChannelMessage> ingest(IngestDiscordCommand command) {
        try {
            Comment comment = Comment.create(
                    command.messageId(),
                    command.channelId(),
                    command.authorId(),
                    command.authorName(),
                    command.content(),
                    command.sentTime(),
                    Source.DISCORD);
            // Why: no PII in logs, only ids + source.
            log.debug("Ingested discord message messageId={} source={}",
                    comment.messageId(), comment.source());
            // Why DTO: the domain Comment stays internal; adapters consume this view
            // (the listener needs accepted/discarded, 002 will need the normalized text).
            return Optional.of(ChannelMessage.from(comment));
        } catch (InvalidCommentException e) {
            log.debug("Discarded discord message reason={}", e.getMessage());
            return Optional.empty();
        }
    }
}
