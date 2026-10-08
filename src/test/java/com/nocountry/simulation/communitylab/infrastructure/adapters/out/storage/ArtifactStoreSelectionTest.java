package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.objectstorage.ObjectStorage;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring tests: for every OCI_AUTH_MODE exactly one ArtifactStore exists, so PackageRunService
 * never gets zero or two candidates.
 * Derivado de: spec 006 RF-02 (enmienda 2026-10-08: none -> OCI_NOT_CONFIGURED degradado; session-token /
 * instance-principal -> adapter OCI real) + plan 006 §1 (ArtifactStoreAdapter reemplazado por el adapter OCI).
 */
@DisplayName("ArtifactStore selection")
class ArtifactStoreSelectionTest {

    // Why the placeholder auto-config: it is what resolves ${...} inside @ConditionalOnExpression in the real app.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Collaborators.class, ArtifactStoreAdapter.class, OciObjectStorageAdapter.class);

    @Test
    @DisplayName("Dado oci.auth-mode ausente, cuando arranca, entonces solo existe el adapter degradado")
    void onlyDegradedAdapterWhenModeAbsent() {
        // Dado un contexto sin oci.auth-mode (las tres condiciones deben coincidir en el default none)
        runner.run(context -> {
            // Entonces exactamente 1 ArtifactStore y es el degradado
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ArtifactStore.class);
            assertThat(context.getBean(ArtifactStore.class)).isInstanceOf(ArtifactStoreAdapter.class);
        });
    }

    @Test
    @DisplayName("Dado oci.auth-mode=none, cuando arranca, entonces solo existe el adapter degradado")
    void onlyDegradedAdapterWhenModeIsNone() {
        // Dado OCI apagado explicitamente
        runner.withPropertyValues("oci.auth-mode=none").run(context -> {
            // Entonces exactamente 1 ArtifactStore y es el degradado
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ArtifactStore.class);
            assertThat(context.getBean(ArtifactStore.class)).isInstanceOf(ArtifactStoreAdapter.class);
        });
    }

    @Test
    @DisplayName("Dado oci.auth-mode=session-token, cuando arranca, entonces solo existe el adapter OCI")
    void onlyOciAdapterWhenModeIsSessionToken() {
        // Dado OCI activo en local
        runner.withPropertyValues("oci.auth-mode=session-token").run(context -> {
            // Entonces exactamente 1 ArtifactStore y es el real (sin NoUniqueBeanDefinitionException)
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ArtifactStore.class);
            assertThat(context.getBean(ArtifactStore.class)).isInstanceOf(OciObjectStorageAdapter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OciProperties.class)
    static class Collaborators {

        // Why a mock client: this test checks which adapter is wired, not OCI auth (covered by OciConfigTest).
        @Bean
        ObjectStorage objectStorage() {
            return mock(ObjectStorage.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
