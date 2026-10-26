package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.retrier.RetryConfiguration;
import com.oracle.bmc.waiter.ExponentialBackoffDelayStrategyWithJitter;
import com.oracle.bmc.waiter.MaxAttemptsTerminationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

/**
 * This class is used to configure OCI services.
 * The client OCI only it's created when OCI_AUTH_MODE is different than none
 */
@Configuration
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class OciConfig {

    private static final long RETRY_MAX_DELAY_MILLIS = 2_000;

    @Bean
    public BasicAuthenticationDetailsProvider ociAuthProvider(OciProperties properties) throws IOException {
        return switch (properties.authMode()) {
            case SESSION_TOKEN -> {
                requireConfigured(properties.configProfile(), "OCI_CONFIG_PROFILE", properties.authMode());
                yield new SessionTokenAuthenticationDetailsProvider(properties.configProfile());
            }
            case INSTANCE_PRINCIPAL -> InstancePrincipalsAuthenticationDetailsProvider.builder().build();
            case NONE -> throw new IllegalStateException("OCI_AUTH_MODE is set to NONE");
        };
    }

    // Why explicit limits (spec 010 RF-06): the SDK defaults are 60s read + up to 8 attempts with 30s backoff, so one slow
    // call held one of the 3 oci-storage threads for minutes. Only the SDK can really stop its blocking HTTP call;
    // cancelling the caller's Future leaves the thread busy.
    @Bean
    public ClientConfiguration objectStorageClientConfiguration(
            @Value("${oci.connect-timeout:5s}") Duration connectTimeout,
            @Value("${oci.read-timeout:20s}") Duration readTimeout,
            @Value("${oci.max-attempts:2}") int maxAttempts) {
        return storageClientConfiguration(connectTimeout, readTimeout, maxAttempts);
    }

    public static ClientConfiguration storageClientConfiguration(Duration connectTimeout, Duration readTimeout, int maxAttempts) {
        requirePositive(connectTimeout, "OCI_CONNECT_TIMEOUT");
        requirePositive(readTimeout, "OCI_READ_TIMEOUT");
        if (maxAttempts < 1) {
            throw new IllegalStateException("OCI_MAX_ATTEMPTS must be at least 1");
        }
        return ClientConfiguration.builder()
                .connectionTimeoutMillis(Math.toIntExact(connectTimeout.toMillis()))
                .readTimeoutMillis(Math.toIntExact(readTimeout.toMillis()))
                .retryConfiguration(RetryConfiguration.builder()
                        .terminationStrategy(new MaxAttemptsTerminationStrategy(maxAttempts))
                        // Why a 2s cap: a retry must stay well inside a PATCH a person is waiting for
                        .delayStrategy(new ExponentialBackoffDelayStrategyWithJitter(RETRY_MAX_DELAY_MILLIS))
                        .build())
                .build();
    }

    @Bean
    public ObjectStorage objectStorage(BasicAuthenticationDetailsProvider provider, OciProperties properties,
                                       ClientConfiguration objectStorageClientConfiguration){
        requireConfigured(properties.region(), "OCI_REGION", properties.authMode());
        requireConfigured(properties.bucket(), "OCI_BUCKET", properties.authMode());
        requireConfigured(properties.namespace(), "OCI_NAMESPACE", properties.authMode());
        requireConfigured(properties.prefix(), "OCI_PREFIX", properties.authMode());
        return ObjectStorageClient.builder()
                .region(properties.region())
                .configuration(objectStorageClientConfiguration)
                .build(provider);
    }

    @Bean
    public Monitoring monitoring(BasicAuthenticationDetailsProvider provider, OciProperties properties){
        requireConfigured(properties.region(), "OCI_REGION", properties.authMode());
        requireConfigured(properties.compartmentId(), "OCI_COMPARTMENT_ID", properties.authMode());
        return MonitoringClient.builder()
                .region(properties.region())
                .build(provider);
    }

    private static void requirePositive(Duration value, String envName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(envName + " must be positive");
        }
    }

    private void requireConfigured(String value, String envName, OciAuthMode mode){
        if(value == null || value.isBlank() || value.startsWith("${")){
            throw new IllegalStateException(envName + " is required when OCI_AUTH_MODE=" + mode);
        }
    }

}
