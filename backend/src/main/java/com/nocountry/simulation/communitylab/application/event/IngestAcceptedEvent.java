package com.nocountry.simulation.communitylab.application.event;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;

public record IngestAcceptedEvent(ChannelMessage message) {
}
