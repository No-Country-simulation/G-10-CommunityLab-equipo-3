package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;

import java.util.Optional;

public interface IngestUseCaseTelegram {
    Optional<ChannelMessage> ingest(IngestTelegramCommand command);
}
