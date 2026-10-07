package com.nocountry.simulation.communitylab.application.port.in;

import com.nocountry.simulation.communitylab.domain.enums.Source;

public interface PackageRunUseCase {
    void flushIfFull(Source source, long currentBytes);
    void flushScheduled();
}
