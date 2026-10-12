package com.nocountry.simulation.communitylab.application.services.revision;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RevisionPostService implements RevisePostUseCase {

    @Override
    public void revise(RevisePostCommand command) {
        // Only for development, wait to implement,
        log.info("revision received, not stored: source={} batchId={} messageId={} expectedVersion={}",
                command.source(), command.batchId(), command.messageId(), command.expectedVersion());
    }
}
