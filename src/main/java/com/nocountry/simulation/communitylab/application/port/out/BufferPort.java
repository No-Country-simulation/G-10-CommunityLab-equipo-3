package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;

// Resolve test
public interface BufferPort {
    String getCurrentBatchId();
    void appendToBatch(ChannelMessage channelMessage);
}
