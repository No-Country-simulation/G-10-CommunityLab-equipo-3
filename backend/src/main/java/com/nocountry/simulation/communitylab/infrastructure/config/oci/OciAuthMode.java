package com.nocountry.simulation.communitylab.infrastructure.config.oci;

/**
* This enum is used to specify the authentication mode for OCI services.
 * For dev is used SESSION_TOKEN, while for production INSTANCE_PRINCIPAL.
* */
public enum OciAuthMode {
    NONE,
    SESSION_TOKEN,
    INSTANCE_PRINCIPAL
}
