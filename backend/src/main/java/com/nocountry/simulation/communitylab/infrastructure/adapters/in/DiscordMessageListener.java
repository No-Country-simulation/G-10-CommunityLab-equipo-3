package com.nocountry.simulation.communitylab.infrastructure.adapters.in;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseDiscord;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
// This class extends ListenerAdapter from dependency Discord (JDA)
public class DiscordMessageListener extends ListenerAdapter {
    private final IngestUseCaseDiscord useCase;
    // Only channel #listen
    private final String channelId;

    public DiscordMessageListener(
            IngestUseCaseDiscord useCase,
            @Value("${discord.channel-id:}") String channelId
    ){
        this.useCase = useCase;
        this.channelId = channelId;
    }

    // Override method from ListenerAdapter
    @Override
    public void onMessageReceived(MessageReceivedEvent event){

        // TODO DEV-LOG provisional: eliminar antes de PR
        String receivedChannelId = event.getChannel().getId();
        boolean isBot = event.getAuthor().isBot();
        String rawForLog = event.getMessage().getContentRaw();
        int rawLengthForLog = rawForLog != null ? rawForLog.length() : -1;
        log.debug("DEV-LOG Discord event received: receivedChannelId={} isBot={} rawLength={}",
                receivedChannelId, isBot, rawLengthForLog);

        // Filter 1 -> AntiBots
        if(event.getAuthor().isBot()) {
            log.debug("DEV-LOG Discarded reason=bot");
            return;
        }

        // Filter 2 -> Verify channel Only #Listen
        if(!event.getChannel().getId().equals(channelId)) {
            log.debug("DEV-LOG Discarded reason=channel-mismatch expectedChannelId={} receivedChannelId={}",
                    channelId, receivedChannelId);
            return;
        }

        // Filter 3 -> Verify message is not empty
        log.debug("DEV-LOG Content check rawLength={}", rawLengthForLog);
        if(event.getMessage().getContentRaw().trim().isEmpty()) {
            log.debug("DEV-LOG Discarded reason=empty-content");
            return;
        }

        // Traduce information to Command (raw content; truncation lives only in domain)
        IngestDiscordCommand message = new IngestDiscordCommand(
                event.getMessage().getId(),
                receivedChannelId,
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getMessage().getContentRaw(),
                event.getMessage().getTimeCreated().toInstant()
        );

        var result = useCase.ingest(message);
        // Why: present = accepted and normalized, empty = discarded by the use case.
        log.debug("DEV-LOG Forwarded to useCase accepted={}", result != null && result.isPresent());
    }
}
