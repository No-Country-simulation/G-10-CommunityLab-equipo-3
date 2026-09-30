package com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Instant;


@Component
public class TelegramMessageMapper {

    public IngestTelegramCommand toIngestTelegramCommand(Message message) {
        return new IngestTelegramCommand(
                message.getMessageId(),
                message.getChat().getId(),
                message.getFrom().getId(),
                message.getFrom().getUserName(),
                message.getText(),
                Instant.ofEpochSecond(message.getDate())
        );
    }
}
