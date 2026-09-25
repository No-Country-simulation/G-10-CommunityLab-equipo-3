package com.nocountry.simulation.communitylab.application.port.in;

import java.util.Optional;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;

public interface IngestUseCaseDiscord {
    Optional<ChannelMessage> ingest(IngestDiscordCommand command);
}
