package com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.springframework.stereotype.Component;

@Component
public class DiscordMessageMapper {

    public IngestDiscordCommand fromMessageReceivedEvent(MessageReceivedEvent event){
        return new IngestDiscordCommand(
                event.getMessageId(),
                event.getChannel().getId(),
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getMessage().getContentRaw(),
                event.getMessage().getTimeCreated().toInstant()
        );
    }
}
