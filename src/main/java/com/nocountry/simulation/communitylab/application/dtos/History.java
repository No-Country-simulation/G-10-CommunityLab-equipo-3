package com.nocountry.simulation.communitylab.application.dtos;

import java.util.List;

public record History(
        List<PackageCompleted> packages,
        List<ResponseClient> pending
) {
    public History {
        packages = List.copyOf(packages);
        pending = List.copyOf(pending);
    }
    public static History empty() {
        return new History(List.of(), List.of());
    }
}
