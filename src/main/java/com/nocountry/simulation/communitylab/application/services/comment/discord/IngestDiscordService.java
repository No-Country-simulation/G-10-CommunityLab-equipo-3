package com.nocountry.simulation.communitylab.application.services.comment.discord;

import java.util.Optional;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseDiscord;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IngestDiscordService implements IngestUseCaseDiscord {

    private final BufferPort bufferPort;
    private final ApplicationEventPublisher events;

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

            String batchId = bufferPort.getCurrentBatchId();

            ChannelMessage message = ChannelMessage.from(comment, batchId);

            // Why: sync publish is in-memory and keeps p95<300ms; the heavy
            // LLM work runs later in the 002 @Async @EventListener consumer.
            events.publishEvent(new IngestAcceptedEvent(message));
            return Optional.of(message);
        } catch (InvalidCommentException e) {
            return Optional.empty();
        }
    }
}
