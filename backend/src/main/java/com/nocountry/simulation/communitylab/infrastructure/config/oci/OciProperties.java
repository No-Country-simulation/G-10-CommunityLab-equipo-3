package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oci")
public record OciProperties(
        OciAuthMode authMode,
        String configProfile,
        String bucket,
        String region,
        String namespace,
        String prefix,
        int parTtlDays
) {
}
