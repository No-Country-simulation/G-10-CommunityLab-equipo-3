package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * This class is used to configure OCI services.
 * The client OCI only it's created when OCI_AUTH_MODE is different than none
 */
@Configuration
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class OciConfig {

    @Bean
    public BasicAuthenticationDetailsProvider ociAuthProvider(OciProperties properties) throws IOException {
        return switch (properties.authMode()) {
            case SESSION_TOKEN -> new SessionTokenAuthenticationDetailsProvider(properties.configProfile());
            case INSTANCE_PRINCIPAL -> InstancePrincipalsAuthenticationDetailsProvider.builder().build();
            case NONE -> throw new IllegalStateException("OCI_AUTH_MODE is set to NONE");
        };
    }

    @Bean
    public ObjectStorage objectStorage(BasicAuthenticationDetailsProvider provider, OciProperties properties){
        requireConfigured(properties.region(), "OCI_REGION");
        requireConfigured(properties.bucket(), "OCI_BUCKET");
        requireConfigured(properties.namespace(), "OCI_NAMESPACE");
        requireConfigured(properties.prefix(), "OCI_PREFIX");
        return ObjectStorageClient.builder()
                .region(properties.region())
                .build(provider);
    }

    private void requireConfigured(String value, String envName){
        if(value == null || value.isBlank() || value.startsWith("${")){
            throw new IllegalStateException("The Configuration is null " + envName);
        }
    }

}
