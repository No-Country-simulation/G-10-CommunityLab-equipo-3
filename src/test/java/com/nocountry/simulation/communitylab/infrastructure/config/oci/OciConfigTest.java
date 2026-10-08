package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.StringPrivateKeySupplier;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests for the OCI client wiring: when the client exists, and that it really builds.
 * Derivado de: spec 006 RF-02 (enmienda 2026-10-08: none -> sin cliente, session-token/instance-principal
 * -> cliente; fallo explicito, no silencioso) + RNF-02 + plan 006 §3 (TASK-006-00: Jersey 3.0.8 alineado,
 * verificado en runtime al construir el cliente real).
 */
@DisplayName("OciConfig")
class OciConfigTest {

    private static final String TEST_NAMESPACE = "test-namespace";

    @Nested
    @DisplayName("Activacion por OCI_AUTH_MODE")
    class Activation {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class, OciConfig.class);

        @Test
        @DisplayName("Dado oci.auth-mode ausente, cuando arranca, entonces no hay proveedor ni cliente OCI")
        void noClientWhenModeAbsent() {
            // Dado un entorno sin oci.auth-mode
            // Cuando arranca, entonces la condicion cae en el default none
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(BasicAuthenticationDetailsProvider.class);
                assertThat(context).doesNotHaveBean(ObjectStorage.class);
            });
        }

        @ParameterizedTest(name = "oci.auth-mode={0}")
        @ValueSource(strings = {"none", "NONE", "None"})
        @DisplayName("Dado oci.auth-mode none (cualquier mayuscula), cuando arranca, entonces no hay cliente OCI")
        void noClientWhenModeIsNone(String mode) {
            // Dado el modo none escrito de cualquier forma
            runner.withPropertyValues("oci.auth-mode=" + mode)
                    // Cuando arranca, entonces OciConfig no se carga (ni se toca 169.254.169.254)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(ObjectStorage.class);
                    });
        }

        @Test
        @DisplayName("Dado session-token con un perfil inexistente, cuando arranca, entonces falla de forma explicita")
        void failsExplicitlyWhenSessionProfileIsMissing() {
            // Dado session-token apuntando a un perfil que no existe en ~/.oci/config (o sin fichero, en CI)
            runner.withPropertyValues(
                            "oci.auth-mode=session-token",
                            "oci.config-profile=communitylab-test-missing-profile",
                            "oci.bucket=MessagesUsers",
                            "oci.region=sa-saopaulo-1",
                            "oci.namespace=" + TEST_NAMESPACE,
                            "oci.prefix=paquetes/")
                    // Cuando arranca, entonces no arranca a medias con un cliente inservible
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("ociAuthProvider");
                    });
        }
    }

    @Nested
    @DisplayName("Construccion del cliente")
    class ClientCreation {

        private final OciConfig config = new OciConfig();

        @Test
        @DisplayName("Dado OCI configurado y un proveedor valido, cuando se crea, entonces devuelve un ObjectStorageClient real")
        void buildsRealClient() throws Exception {
            // Dado configuracion completa y un proveedor con clave generada (sin ~/.oci, sin red)
            OciProperties properties = properties("MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/");

            // Cuando se construye el cliente real (Jersey 3.0.8 + conector Apache en runtime)
            try (ObjectStorage client = config.objectStorage(fakeProvider(), properties)) {
                // Entonces es el cliente del SDK, no un stub
                assertThat(client).isInstanceOf(ObjectStorageClient.class);
            }
        }

        @Test
        @DisplayName("Dado el cliente real, cuando hace una peticion firmada a un stub HTTP local, entonces la pila Jersey+Apache responde")
        void realClientCompletesSignedHttpRoundTrip() throws Exception {
            // Why a real request: building the client never touches the HTTP connector,
            // so only a round trip proves the Jersey/connector versions work together (TASK-006-00).
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AtomicReference<String> authorization = new AtomicReference<>();
            server.createContext("/n", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = ("\"" + TEST_NAMESPACE + "\"").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();

            // Dado el cliente real apuntando al stub local en vez de a OCI
            OciProperties properties = properties("MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/");
            try (ObjectStorage client = config.objectStorage(fakeProvider(), properties)) {
                client.setEndpoint("http://localhost:" + server.getAddress().getPort());

                // Cuando hace GetNamespace
                String namespace = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();

                // Entonces la respuesta se deserializa y la peticion salio firmada por el proveedor
                assertThat(namespace).isEqualTo(TEST_NAMESPACE);
                assertThat(authorization.get()).startsWith("Signature");
            } finally {
                server.stop(0);
            }
        }

        @ParameterizedTest(name = "{0}=\"{1}\"")
        @MethodSource("com.nocountry.simulation.communitylab.infrastructure.config.oci.OciConfigTest#missingRequiredValues")
        @DisplayName("Dado OCI activo con un valor requerido vacio o sin resolver, cuando se crea, entonces falla nombrando la variable")
        void failsFastOnMissingRequiredValue(String envName, String badValue) {
            // Dado un valor requerido nulo, en blanco o con el placeholder literal "${...}"
            OciProperties properties = switch (envName) {
                case "OCI_REGION" -> properties("MessagesUsers", badValue, TEST_NAMESPACE, "paquetes/");
                case "OCI_BUCKET" -> properties(badValue, "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/");
                case "OCI_NAMESPACE" -> properties("MessagesUsers", "sa-saopaulo-1", badValue, "paquetes/");
                case "OCI_PREFIX" -> properties("MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, badValue);
                default -> throw new IllegalArgumentException(envName);
            };

            // Cuando se crea, entonces falla antes de hablar con OCI y dice que variable corregir
            assertThatThrownBy(() -> config.objectStorage(fakeProvider(), properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(envName);
        }

        @Test
        @DisplayName("Dado modo NONE, cuando se pide el proveedor, entonces falla (OciConfig no debe cargarse con none)")
        void providerRejectsNoneMode() {
            // Dado un modo none que llegara al bean pese a la condicion de clase
            OciProperties properties = new OciProperties(OciAuthMode.NONE, null,
                    "MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/", 7);

            // Cuando se pide el proveedor, entonces se niega en vez de inventar una identidad
            assertThatThrownBy(() -> config.ociAuthProvider(properties))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    static Stream<Arguments> missingRequiredValues() {
        return Stream.of("OCI_REGION", "OCI_BUCKET", "OCI_NAMESPACE", "OCI_PREFIX")
                .flatMap(env -> Stream.of(
                        Arguments.of(env, null),
                        Arguments.of(env, "   "),
                        Arguments.of(env, "${" + env + "}")));
    }

    private static OciProperties properties(String bucket, String region, String namespace, String prefix) {
        return new OciProperties(OciAuthMode.SESSION_TOKEN, "dev", bucket, region, namespace, prefix, 7);
    }

    // Why a generated key: building the client must not depend on the developer's ~/.oci or a live session.
    private static BasicAuthenticationDetailsProvider fakeProvider() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return SimpleAuthenticationDetailsProvider.builder()
                .tenantId("ocid1.tenancy.oc1..test")
                .userId("ocid1.user.oc1..test")
                .fingerprint("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff")
                .privateKeySupplier(new StringPrivateKeySupplier(pem))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OciProperties.class)
    static class PropertiesOnly {
    }
}
