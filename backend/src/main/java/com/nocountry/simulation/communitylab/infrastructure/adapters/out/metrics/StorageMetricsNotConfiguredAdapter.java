package com.nocountry.simulation.communitylab.infrastructure.adapters.out.metrics;

import com.nocountry.simulation.communitylab.application.dtos.BucketMetrics;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.out.StorageMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
@ConditionalOnExpression("'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class StorageMetricsNotConfiguredAdapter implements StorageMetrics {
    @Override
    public BucketMetrics read(Instant periodStart, Instant now) {
        log.warn("OCI_NOT_CONFIGURED: storage metrics");
        throw new StorageNotConfiguredException("OCI_NOT_CONFIGURED");
    }
}
