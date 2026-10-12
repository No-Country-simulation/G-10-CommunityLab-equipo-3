package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;

public interface PackagePublisher {
    void publishPackage(PackageCompleted packageCompleted);
}
