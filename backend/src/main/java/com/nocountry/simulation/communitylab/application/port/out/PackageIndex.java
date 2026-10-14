package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.time.Instant;
import java.util.List;

public interface PackageIndex {
    void record(PackageCompleted completed);
    List<PackageCompleted> active(Source source, Instant now);
}
