package com.nocountry.simulation.communitylab.infrastructure.adapters.in;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseDiscord;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper.DiscordMessageMapper;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// This class extends ListenerAdapter from dependency Discord (JDA)
public class DiscordMessageListener extends ListenerAdapter {
    private final IngestUseCaseDiscord useCase;
    // Only channel #listen
    private final String channelId;
    private final DiscordMessageMapper discordMessageMapper;

    public DiscordMessageListener(
            IngestUseCaseDiscord useCase,
            @Value("${discord.channel-id:}") String channelId,
            DiscordMessageMapper discordMessageMapper
    ){
        this.useCase = useCase;
        this.channelId = channelId;
        this.discordMessageMapper = discordMessageMapper;
    }

    // Override method from ListenerAdapter
    @Override
    public void onMessageReceived(MessageReceivedEvent event){
        boolean isBot = event.getAuthor().isBot();

        // Filter 1 -> AntiBots
        if(isBot) {
            return;
        }

        // Filter 2 -> Verify channel Only #Listen
        if(!event.getChannel().getId().equals(channelId)) {
            return;
        }

        // Filter 3 -> Verify message is not empty
        if(event.getMessage().getContentRaw().trim().isEmpty()) {
            return;
        }

        // Traduce information using mapper
        IngestDiscordCommand message =  discordMessageMapper.fromMessageReceivedEvent(event);

        useCase.ingest(message);
    }
}
