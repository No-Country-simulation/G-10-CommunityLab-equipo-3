package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding tests for the OCI storage properties (Spring binder only, no OCI SDK, no network).
 * Derivado de: spec 006 RF-02 (enmienda 2026-10-08: OCI_AUTH_MODE none|session-token|instance-principal,
 * default none) + RNF-02 (solo env no-secreta) + plan 006 §3 (enum para fail-fast ante valor invalido).
 */
@DisplayName("OciProperties")
class OciPropertiesTest {

    // Why no OciConfig here: binding must be verified without building any OCI auth provider.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("Dado oci.* completo, cuando se enlaza, entonces los 7 campos llegan al record")
    void bindsAllFields() {
        // Dado un entorno local session-token completo
        runner.withPropertyValues(
                        "oci.auth-mode=session-token",
                        "oci.config-profile=dev",
                        "oci.bucket=MessagesUsers",
                        "oci.region=sa-saopaulo-1",
                        "oci.namespace=test-namespace",
                        "oci.prefix=paquetes/",
                        "oci.par-ttl-days=7")
                // Cuando arranca el contexto
                .run(context -> {
                    // Entonces cada clave kebab-case llega a su componente camelCase
                    OciProperties properties = context.getBean(OciProperties.class);
                    assertThat(properties.authMode()).isEqualTo(OciAuthMode.SESSION_TOKEN);
                    assertThat(properties.configProfile()).isEqualTo("dev");
                    assertThat(properties.bucket()).isEqualTo("MessagesUsers");
                    assertThat(properties.region()).isEqualTo("sa-saopaulo-1");
                    assertThat(properties.namespace()).isEqualTo("test-namespace");
                    assertThat(properties.prefix()).isEqualTo("paquetes/");
                    assertThat(properties.parTtlDays()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("Dado modo en kebab-case o mayusculas, cuando se enlaza, entonces mapea a la constante del enum")
    void bindsAuthModeWithRelaxedNames() {
        // Dado el modo escrito como en el env o como en Java
        runner.withPropertyValues("oci.auth-mode=instance-principal")
                // Cuando arranca, entonces el relaxed binding lo convierte
                .run(context -> assertThat(context.getBean(OciProperties.class).authMode())
                        .isEqualTo(OciAuthMode.INSTANCE_PRINCIPAL));

        runner.withPropertyValues("oci.auth-mode=NONE")
                .run(context -> assertThat(context.getBean(OciProperties.class).authMode())
                        .isEqualTo(OciAuthMode.NONE));
    }

    @Test
    @DisplayName("Dado un modo con errata, cuando arranca, entonces falla al enlazar oci.auth-mode (no se apaga OCI en silencio)")
    void failsFastOnUnknownAuthMode() {
        // Dado OCI_AUTH_MODE=sesion-token (falta una 's')
        runner.withPropertyValues("oci.auth-mode=sesion-token")
                // Cuando arranca el contexto
                .run(context -> {
                    // Entonces no arranca y el error nombra la clave a corregir
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("oci.auth-mode");
                });
    }

    @Test
    @DisplayName("Dado application.yaml sin OCI_AUTH_MODE en el env, cuando se enlaza, entonces el modo es NONE")
    void defaultsToNoneFromApplicationYaml() {
        // Why assume: the default can only be observed when the variable is absent from this process.
        assumeTrue(System.getenv("OCI_AUTH_MODE") == null, "OCI_AUTH_MODE set in this environment");

        // Dado el application.yaml real del proyecto y ningun OCI_AUTH_MODE
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                // Cuando arranca el contexto
                .run(context -> {
                    // Entonces el portatil/CI queda sin OCI (RF-02: degradar, no tumbar)
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OciProperties.class).authMode()).isEqualTo(OciAuthMode.NONE);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OciProperties.class)
    static class PropertiesOnly {
    }
}
