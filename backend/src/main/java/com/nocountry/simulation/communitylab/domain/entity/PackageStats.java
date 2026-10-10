package com.nocountry.simulation.communitylab.domain.entity;

public record PackageStats (
        int received, int analyzed, int fallbackCount, int assetsCount) {
}
