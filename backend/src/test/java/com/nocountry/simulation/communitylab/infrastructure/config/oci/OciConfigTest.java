package com.nocountry.simulation.communitylab.infrastructure.config.oci;

import com.oracle.bmc.waiter.MaxAttemptsTerminationStrategy;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.ClientConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;
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
import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests for the OCI client wiring: when the client exists, and that it really builds.
 * Derivado de: spec 006 RF-02 (enmienda 2026-10-08: none -> sin cliente, session-token/instance-principal
 * -> cliente; fallo explicito, no silencioso) + RNF-02 + plan 006 §3 (TASK-006-00: Jersey 3.0.8 alineado,
 * verificado en runtime al construir el cliente real) + TASK-006-06 (2026-10-12: sin default `dev` en
 * `oci.config-profile`; session-token sin perfil falla nombrando OCI_CONFIG_PROFILE y el modo).
 * Spec 009 plan §1.1 (TASK-009-01): cliente Monitoring con el mismo proveedor y región; exige OCI_COMPARTMENT_ID.
 * Spec 010 RF-06: el cliente Object Storage lleva sus propios límites (conexión, lectura, intentos) y el SDK los cumple.
 */
@DisplayName("OciConfig")
class OciConfigTest {

    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String TEST_COMPARTMENT = "ocid1.compartment.oc1..test";
    // Same limits as application.yaml defaults (5s connect, 20s read, 2 attempts).
    private static final ClientConfiguration STORAGE_CLIENT =
            OciConfig.storageClientConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(20), 2);

    @Nested
    @DisplayName("Activacion por OCI_AUTH_MODE")
    class Activation {

        // Why Boot's conversion service: SpringApplication registers it, and it turns "5s" into the client's Duration
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
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
                assertThat(context).doesNotHaveBean(Monitoring.class);
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
                        assertThat(context).doesNotHaveBean(Monitoring.class);
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

        @Test
        @DisplayName("Dado session-token sin oci.config-profile, cuando arranca, entonces falla nombrando OCI_CONFIG_PROFILE")
        void failsNamingTheProfileVariableWhenAbsent() {
            // Dado session-token sin perfil (application.yaml ya no trae el default "dev")
            runner.withPropertyValues(
                            "oci.auth-mode=session-token",
                            "oci.bucket=MessagesUsers",
                            "oci.region=sa-saopaulo-1",
                            "oci.namespace=" + TEST_NAMESPACE,
                            "oci.prefix=paquetes/")
                    // Cuando arranca, entonces no arranca y la causa dice qué variable falta
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("OCI_CONFIG_PROFILE");
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
            try (ObjectStorage client = config.objectStorage(fakeProvider(), properties, STORAGE_CLIENT)) {
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
            try (ObjectStorage client = config.objectStorage(fakeProvider(), properties, STORAGE_CLIENT)) {
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

            // Cuando se crea, entonces falla antes de hablar con OCI y dice que variable corregir y en qué modo
            assertThatThrownBy(() -> config.objectStorage(fakeProvider(), properties, STORAGE_CLIENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(envName)
                    .hasMessageContaining("OCI_AUTH_MODE=SESSION_TOKEN");
        }

        @Test
        @DisplayName("Dado OCI configurado con compartment, cuando se crea Monitoring, entonces devuelve un MonitoringClient real")
        void buildsRealMonitoringClient() throws Exception {
            // Dado la misma configuracion y el mismo tipo de proveedor que Object Storage (la identidad es la VM)
            OciProperties properties = properties("MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/");

            // Cuando se construye el cliente de metricas
            try (Monitoring client = config.monitoring(fakeProvider(), properties)) {
                // Entonces es el cliente del SDK, no un stub
                assertThat(client).isInstanceOf(MonitoringClient.class);
            }
        }

        @ParameterizedTest(name = "{0}=\"{1}\"")
        @MethodSource("com.nocountry.simulation.communitylab.infrastructure.config.oci.OciConfigTest#missingMonitoringValues")
        @DisplayName("Dado OCI activo sin region o compartment, cuando se crea Monitoring, entonces falla nombrando la variable")
        void monitoringFailsFastOnMissingRequiredValue(String envName, String badValue) {
            // Dado la region o el compartment nulos, en blanco o con el placeholder literal "${...}"
            OciProperties properties = switch (envName) {
                case "OCI_REGION" -> properties("MessagesUsers", badValue, TEST_NAMESPACE, "paquetes/");
                case "OCI_COMPARTMENT_ID" -> new OciProperties(OciAuthMode.SESSION_TOKEN, "dev",
                        "MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/", 7, badValue);
                default -> throw new IllegalArgumentException(envName);
            };

            // Cuando se crea, entonces falla al arrancar y no en la primera consulta de metricas
            assertThatThrownBy(() -> config.monitoring(fakeProvider(), properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(envName)
                    .hasMessageContaining("OCI_AUTH_MODE=SESSION_TOKEN");
        }

        @ParameterizedTest(name = "OCI_CONFIG_PROFILE=\"{0}\"")
        @MethodSource("com.nocountry.simulation.communitylab.infrastructure.config.oci.OciConfigTest#missingProfileValues")
        @DisplayName("Dado session-token sin perfil (vacío o sin resolver), cuando se pide el proveedor, entonces falla nombrando OCI_CONFIG_PROFILE")
        void sessionTokenRequiresAProfile(String badProfile) {
            // Dado session-token sin OCI_CONFIG_PROFILE (sin default en application.yaml queda "${OCI_CONFIG_PROFILE}")
            OciProperties properties = new OciProperties(OciAuthMode.SESSION_TOKEN, badProfile,
                    "MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/", 7, TEST_COMPARTMENT);

            // Cuando se pide el proveedor, entonces falla con un mensaje claro antes de leer ~/.oci/config
            assertThatThrownBy(() -> config.ociAuthProvider(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OCI_CONFIG_PROFILE")
                    .hasMessageContaining("OCI_AUTH_MODE=SESSION_TOKEN");
        }

        @Test
        @DisplayName("Dado modo NONE, cuando se pide el proveedor, entonces falla (OciConfig no debe cargarse con none)")
        void providerRejectsNoneMode() {
            // Dado un modo none que llegara al bean pese a la condicion de clase
            OciProperties properties = new OciProperties(OciAuthMode.NONE, null,
                    "MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/", 7, TEST_COMPARTMENT);

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

    static Stream<Arguments> missingMonitoringValues() {
        return Stream.of("OCI_REGION", "OCI_COMPARTMENT_ID")
                .flatMap(env -> Stream.of(
                        Arguments.of(env, null),
                        Arguments.of(env, "   "),
                        Arguments.of(env, "${" + env + "}")));
    }

    static Stream<String> missingProfileValues() {
        return Stream.of(null, "   ", "${OCI_CONFIG_PROFILE}");
    }

    @Nested
    @DisplayName("Límites del cliente Object Storage (spec 010 RF-06)")
    class StorageClientLimits {

        private final OciConfig config = new OciConfig();

        @Test
        @DisplayName("Dado los valores por defecto, cuando se crea la configuración, entonces 5s de conexión, 20s de lectura y como mucho 2 intentos")
        void defaultsBoundEveryCall() {
            // Cuando se crea con los defaults del application.yaml
            ClientConfiguration client = config.objectStorageClientConfiguration(
                    Duration.ofSeconds(5), Duration.ofSeconds(20), 2);

            // Entonces un hilo oci-storage queda libre en ~2 x (5 + 20) s + 2 s, no en minutos (SDK: 60 s x 8 intentos)
            assertThat(client.getConnectionTimeoutMillis()).isEqualTo(5_000);
            assertThat(client.getReadTimeoutMillis()).isEqualTo(20_000);
            assertThat(client.getRetryConfiguration().getTerminationStrategy())
                    .isInstanceOfSatisfying(MaxAttemptsTerminationStrategy.class,
                            strategy -> assertThat(strategy.getMaxAttempts()).isEqualTo(2));
        }

        @Test
        @DisplayName("Dado un límite cero, negativo o sin intentos, cuando se crea, entonces falla al arrancar nombrando la variable")
        void invalidLimitsFailFast() {
            assertThatThrownBy(() -> OciConfig.storageClientConfiguration(Duration.ZERO, Duration.ofSeconds(20), 2))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OCI_CONNECT_TIMEOUT");
            assertThatThrownBy(() -> OciConfig.storageClientConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(-1), 2))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OCI_READ_TIMEOUT");
            assertThatThrownBy(() -> OciConfig.storageClientConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(20), 0))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OCI_MAX_ATTEMPTS");
        }

        @Test
        @DisplayName("Dado OCI que no responde, cuando el cliente real hace una petición, entonces el propio SDK la corta por read-timeout y libera el hilo")
        void realClientStopsAHungCallByItself() throws Exception {
            // Why a real HTTP round trip: only it proves this SDK version (Jersey + Apache connector) honors the limit;
            // a cancelled Future would leave the call running.
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger requests = new AtomicInteger();
            server.createContext("/n", exchange -> {
                requests.incrementAndGet();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            // Dado un cliente con 300 ms de lectura y 2 intentos
            ClientConfiguration limits = OciConfig.storageClientConfiguration(Duration.ofSeconds(1), Duration.ofMillis(300), 2);
            OciProperties properties = properties("MessagesUsers", "sa-saopaulo-1", TEST_NAMESPACE, "paquetes/");
            try (ObjectStorage client = config.objectStorage(fakeProvider(), properties, limits)) {
                client.setEndpoint("http://localhost:" + server.getAddress().getPort());

                // Cuando OCI no contesta
                long start = System.nanoTime();
                assertThatThrownBy(() -> client.getNamespace(GetNamespaceRequest.builder().build()))
                        .isInstanceOfSatisfying(BmcException.class, e -> assertThat(e.isTimeout()).isTrue());
                Duration took = Duration.ofNanos(System.nanoTime() - start);

                // Entonces el SDK corta cada intento y reintenta como mucho una vez: segundos, no minutos
                assertThat(requests.get()).isEqualTo(2);
                assertThat(took).isLessThan(Duration.ofSeconds(5));
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    private static OciProperties properties(String bucket, String region, String namespace, String prefix) {
        return new OciProperties(OciAuthMode.SESSION_TOKEN, "dev", bucket, region, namespace, prefix, 7, TEST_COMPARTMENT);
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
