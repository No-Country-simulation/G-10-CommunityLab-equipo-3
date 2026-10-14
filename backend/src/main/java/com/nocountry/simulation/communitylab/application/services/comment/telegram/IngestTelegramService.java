package com.nocountry.simulation.communitylab.application.services.comment.telegram;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseTelegram;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class IngestTelegramService implements IngestUseCaseTelegram {

    private final BufferPort bufferPort;
    private final ApplicationEventPublisher events;

    @Override
    public Optional<ChannelMessage> ingest(IngestTelegramCommand command) {
        try{
            Comment comment = Comment.create(
                    command.messageId().toString(),
                    command.channelId().toString(),
                    command.authorId().toString(),
                    command.authorName(),
                    command.content(),
                    command.sentTime(),
                    Source.TELEGRAM
            );

            String batchId = bufferPort.getCurrentBatchId(Source.TELEGRAM);

            ChannelMessage message = ChannelMessage.from(comment, batchId);

            events.publishEvent(new IngestAcceptedEvent(message));

            // Why ids only (Q3): no author/content in ingest logs.
            log.info("ingest accepted: messageId={} batchId={} source=TELEGRAM",
                    comment.messageId(), batchId);
            return Optional.of(message);
        } catch (InvalidCommentException e) {
            log.warn("ingest rejected: messageId={} cause=invalid", command.messageId());
            return Optional.empty();
        }
    }
}
