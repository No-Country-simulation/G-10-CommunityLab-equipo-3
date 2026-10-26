package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.adapters.out.cache.RedisPackageCache;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciAuthMode;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.CreatePreauthenticatedRequestResponse;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Storage adapter + package cache: Redis only ever holds a package the bucket has confirmed (revision GET or If-Match
 * PUT, never the flush), a hit skips the GET, the cache is evicted before every rewrite, and every SDK call runs on a
 * bounded platform pool (how long a call may take is bounded by the client itself, see OciConfigTest). OCI client and cache are mocked at the boundary.
 *
 * <p>Derivado de: spec 010 RF-06 (llamadas al SDK en 3 hilos de plataforma), RF-08 (caché solo tras confirmación de OCI, SET NX tras GET,
 * hit sin GET, If-Match como validación), RF-09 (lectura fresca) + plan 010 §C, §D.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OciObjectStorageAdapter (package cache)")
class OciObjectStorageAdapterCacheTest {

    private static final String BATCH_ID = "batch-1";
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final String STORED_ETAG = "etag-stored";
    private static final String NEW_ETAG = "etag-new";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int ONE_MB = 1_048_576;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private RedisPackageCache packageCache;

    private OciObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OciObjectStorageAdapter(objectStorage, properties(), JSON, packageCache);
    }

    @AfterEach
    void tearDown() {
        adapter.destroy();
    }

    // ================= load =================

    @Test
    @DisplayName("Dado el paquete en caché, cuando se carga, entonces se devuelve sin llamar a OCI")
    void cacheHitSkipsTheBucket() {
        // Dado una copia ya confirmada por OCI
        StoredPackage cached = new StoredPackage(discordPackage(), STORED_ETAG);
        when(packageCache.get(Source.DISCORD, BATCH_ID)).thenReturn(Optional.of(cached));

        // Cuando se carga
        Optional<StoredPackage> loaded = adapter.load(Source.DISCORD, BATCH_ID);

        // Entonces es la copia del caché, con su etag para el If-Match, y ningún GET gasta cuota
        assertThat(loaded).contains(cached);
        verify(objectStorage, never()).getObject(any());
    }

    @Test
    @DisplayName("Dado el caché vacío, cuando se carga, entonces se lee de OCI y se cachea con el etag de esa lectura")
    void cacheMissReadsTheBucketThenCaches() {
        // Dado el caché vacío y el paquete en OCI
        AssetPackage original = discordPackage();
        objectHolds(original);

        // Cuando se carga
        Optional<StoredPackage> loaded = adapter.load(Source.DISCORD, BATCH_ID);

        // Entonces viene del bucket y solo entonces entra al caché
        assertThat(loaded).contains(new StoredPackage(original, STORED_ETAG));
        InOrder order = inOrder(objectStorage, packageCache);
        order.verify(objectStorage).getObject(any());
        order.verify(packageCache).putIfAbsent(original, STORED_ETAG);
        // Why never a plain SET here: a concurrent replace may have cached a newer version meanwhile
        verify(packageCache, never()).put(any(), anyString());
    }

    @Test
    @DisplayName("Dado un paquete que no existe en OCI (404), cuando se carga, entonces no se cachea nada")
    void missingObjectIsNeverCached() {
        // Dado el caché vacío y OCI sin el objeto
        when(objectStorage.getObject(any())).thenThrow(bmcException(404));

        // Cuando se carga / Entonces vacío y Redis no guarda un paquete que el bucket no tiene
        assertThat(adapter.load(Source.DISCORD, BATCH_ID)).isEmpty();
        verify(packageCache, never()).put(any(), anyString());
        verify(packageCache, never()).putIfAbsent(any(), anyString());
    }

    @Test
    @DisplayName("Dado una lectura fresca, cuando se carga, entonces se descarta la copia del caché y se lee de OCI")
    void loadFreshEvictsThenReadsTheBucket() {
        // Dado el paquete en OCI
        AssetPackage original = discordPackage();
        objectHolds(original);

        // Cuando se pide una lectura fresca
        Optional<StoredPackage> loaded = adapter.loadFresh(Source.DISCORD, BATCH_ID);

        // Entonces el caché nunca se consulta: se invalida, se lee el bucket y se vuelve a cachear
        assertThat(loaded).contains(new StoredPackage(original, STORED_ETAG));
        InOrder order = inOrder(packageCache, objectStorage);
        order.verify(packageCache).evict(Source.DISCORD, BATCH_ID);
        order.verify(objectStorage).getObject(any());
        order.verify(packageCache).putIfAbsent(original, STORED_ETAG);
        verify(packageCache, never()).get(any(), anyString());
    }

    // ================= save =================

    @Test
    @DisplayName("Dado un PUT confirmado del flush, cuando se guarda el paquete, entonces no se cachea (la copia nace en la primera revisión)")
    void flushedPackageIsNotCached() {
        // Dado OCI aceptando el objeto y creando la PAR
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag(STORED_ETAG).build());
        parSucceeds();

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces el flush sigue igual y Redis no gasta ~900KB por cada paquete que quizá nadie revise (RNF-06)
        assertThat(result.ok()).isTrue();
        verify(packageCache, never()).put(any(), anyString());
        verify(packageCache, never()).putIfAbsent(any(), anyString());
    }

    @Test
    @DisplayName("Dado un PUT fallido, cuando se guarda el paquete, entonces no se cachea")
    void failedUploadIsNeverCached() {
        // Dado OCI rechazando el objeto
        when(objectStorage.putObject(any())).thenThrow(bmcException(500));

        // Cuando se guarda / Entonces Redis no tiene un paquete que el bucket no tiene
        assertThat(adapter.save(discordPackage()).ok()).isFalse();
        verify(packageCache, never()).put(any(), anyString());
    }

    @Test
    @DisplayName("Dado un paquete ya subido (412), cuando se guarda, entonces no se cachea porque OCI no devuelve su etag")
    void alreadyStoredPackageIsNotCached() {
        // Dado el reintento de un lote ya subido
        when(objectStorage.putObject(any())).thenThrow(bmcException(412));
        parSucceeds();

        // Cuando se guarda / Entonces sin etag no hay copia que luego pueda reescribirse con If-Match
        adapter.save(discordPackage());
        verify(packageCache, never()).put(any(), anyString());
    }

    // ================= replace =================

    @Test
    @DisplayName("Dado un reemplazo confirmado, cuando se reescribe, entonces el caché guarda el paquete nuevo con el etag nuevo")
    void replacedPackageRefreshesTheCache() {
        // Dado OCI aceptando el If-Match
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag(NEW_ETAG).build());
        AssetPackage revised = discordPackage();

        // Cuando se reescribe
        ReplaceResult result = adapter.replace(revised, STORED_ETAG);

        // Entonces la siguiente revisión puede evitar el GET
        assertThat(result).isEqualTo(ReplaceResult.REPLACED);
        verify(packageCache).put(revised, NEW_ETAG);
    }

    @Test
    @DisplayName("Dado un reemplazo, cuando se reescribe, entonces el caché se invalida antes del PUT y solo después se guarda la versión nueva")
    void cacheIsEvictedBeforeThePutAndRefilledAfter() {
        // Dado OCI aceptando el If-Match
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag(NEW_ETAG).build());
        AssetPackage revised = discordPackage();

        // Cuando se reescribe
        adapter.replace(revised, STORED_ETAG);

        // Entonces si el put final fallara, el caché quedaría vacío y nunca con la versión vieja
        InOrder order = inOrder(packageCache, objectStorage);
        order.verify(packageCache).evict(Source.DISCORD, BATCH_ID);
        order.verify(objectStorage).putObject(any());
        order.verify(packageCache).put(revised, NEW_ETAG);
    }

    @Test
    @DisplayName("Dado un conflicto (412), cuando se reescribe, entonces el caché ya está invalidado y no se guarda nada")
    void conflictLeavesTheCacheEmpty() {
        // Dado un etag que ya no es el del bucket (copia desactualizada)
        when(objectStorage.putObject(any())).thenThrow(bmcException(412));

        // Cuando se reescribe
        ReplaceResult result = adapter.replace(discordPackage(), STORED_ETAG);

        // Entonces el reintento del servicio leerá de OCI
        assertThat(result).isEqualTo(ReplaceResult.CONFLICT);
        InOrder order = inOrder(packageCache, objectStorage);
        order.verify(packageCache).evict(Source.DISCORD, BATCH_ID);
        order.verify(objectStorage).putObject(any());
        verify(packageCache, never()).put(any(), anyString());
    }

    @Test
    @DisplayName("Dado un fallo de OCI, cuando se reescribe, entonces FAILED con el caché ya invalidado")
    void failureLeavesTheCacheEmpty() {
        when(objectStorage.putObject(any())).thenThrow(bmcException(500));

        assertThat(adapter.replace(discordPackage(), STORED_ETAG)).isEqualTo(ReplaceResult.FAILED);
        verify(packageCache).evict(Source.DISCORD, BATCH_ID);
        verify(packageCache, never()).put(any(), anyString());
    }

    // ================= size guard =================

    @Test
    @DisplayName("Dado un objeto de 1MB o más sin Content-Length, cuando se carga, entonces se rechaza por tamaño sin leerlo entero ni cachearlo")
    void oversizedBodyWithoutLengthIsRejected() {
        // Dado OCI respondiendo sin Content-Length con un cuerpo de 1MB + 10 bytes
        when(objectStorage.getObject(any())).thenReturn(GetObjectResponse.builder()
                .eTag(STORED_ETAG)
                .inputStream(new ByteArrayInputStream(new byte[ONE_MB + 10]))
                .build());

        // Cuando se carga / Entonces la misma regla de 1MB que con Content-Length
        assertThatThrownBy(() -> adapter.load(Source.DISCORD, BATCH_ID)).isInstanceOf(PackageTooLargeException.class);
        verify(packageCache, never()).putIfAbsent(any(), anyString());
    }

    @Test
    @DisplayName("Dado un paquete normal sin Content-Length, cuando se carga, entonces se lee igual")
    void normalBodyWithoutLengthLoads() {
        // Dado OCI respondiendo sin Content-Length con un paquete válido
        AssetPackage original = discordPackage();
        when(objectStorage.getObject(any())).thenReturn(GetObjectResponse.builder()
                .eTag(STORED_ETAG)
                .inputStream(new ByteArrayInputStream(JSON.writeValueAsBytes(original)))
                .build());

        // Cuando se carga / Entonces nada cambia para el caso normal
        assertThat(adapter.load(Source.DISCORD, BATCH_ID)).contains(new StoredPackage(original, STORED_ETAG));
    }

    // ================= threads =================

    @Test
    @DisplayName("Dado un llamador en un virtual thread, cuando se usa el SDK, entonces la llamada corre en un hilo de plataforma oci-storage")
    void sdkCallsRunOnPlatformThreads() throws Exception {
        // Dado el SDK registrando en qué hilo se ejecuta
        AtomicReference<Thread> sdkThread = new AtomicReference<>();
        when(objectStorage.putObject(any())).thenAnswer(invocation -> {
            sdkThread.set(Thread.currentThread());
            return PutObjectResponse.builder().eTag(NEW_ETAG).build();
        });

        // Cuando un virtual thread (como el PATCH o el pipeline @Async) reescribe un paquete
        try (var virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualThreads.submit(() -> adapter.replace(discordPackage(), STORED_ETAG)).get();
        }

        // Entonces el cliente bloqueante nunca fija un portador de virtual threads (2 núcleos)
        assertThat(sdkThread.get().isVirtual()).isFalse();
        assertThat(sdkThread.get().getName()).startsWith("oci-storage-");
    }

    @Test
    @DisplayName("Dado un error del SDK en su hilo, cuando se reescribe, entonces el código de estado sigue decidiendo el resultado")
    void sdkExceptionsKeepTheirStatusAcrossThreads() {
        // Dado un 412 lanzado dentro del hilo del SDK
        when(objectStorage.putObject(any())).thenThrow(bmcException(412));

        // Cuando se reescribe / Entonces no se convierte en un fallo genérico
        assertThat(adapter.replace(discordPackage(), STORED_ETAG)).isEqualTo(ReplaceResult.CONFLICT);
    }

    @Test
    @DisplayName("Dado dos subidas del flush colgadas, cuando llega un PATCH, entonces aún encuentra un hilo libre y no espera detrás de ellas")
    void revisionIsNotStarvedByTwoFlushes() throws Exception {
        // Dado OCI colgando los PUT create-only del flush (uno por fuente) hasta que el test los libere
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch flushesInside = new CountDownLatch(2);
        when(objectStorage.putObject(any())).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            if ("*".equals(request.getIfNoneMatch())) {
                flushesInside.countDown();
                release.await(5, TimeUnit.SECONDS);
                throw bmcException(500);
            }
            return PutObjectResponse.builder().eTag(NEW_ETAG).build();
        });
        ExecutorService flushes = Executors.newVirtualThreadPerTaskExecutor();
        try {
            flushes.submit(() -> adapter.save(discordPackage()));
            flushes.submit(() -> adapter.save(discordPackage()));
            assertThat(flushesInside.await(2, TimeUnit.SECONDS)).isTrue();

            // Cuando el dashboard revisa un post mientras ambas subidas siguen ocupadas
            long start = System.nanoTime();
            ReplaceResult result = adapter.replace(discordPackage(), STORED_ETAG);

            // Entonces responde enseguida con el tercer hilo
            assertThat(result).isEqualTo(ReplaceResult.REPLACED);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            flushes.shutdown();
        }
    }

    @Test
    @DisplayName("Dado un timeout del cliente OCI, cuando se reescribe, entonces FAILED (502) con el caché ya invalidado y el hilo libre")
    void clientTimeoutIsAFailureWithAnEmptyCache() {
        // Dado el SDK cortando la llamada por su read-timeout (OciConfig), no por una espera del llamador
        when(objectStorage.putObject(any())).thenThrow(new BmcException(true, "read timed out", null, "opc-request-test"));

        // Cuando se reescribe / Entonces el mismo camino que cualquier 5xx: sin copia vieja en Redis
        assertThat(adapter.replace(discordPackage(), STORED_ETAG)).isEqualTo(ReplaceResult.FAILED);
        verify(packageCache).evict(Source.DISCORD, BATCH_ID);
        verify(packageCache, never()).put(any(), anyString());
    }

    // ================= fixtures =================

    private void objectHolds(AssetPackage pkg) {
        byte[] body = JSON.writeValueAsBytes(pkg);
        when(objectStorage.getObject(any())).thenReturn(GetObjectResponse.builder()
                .eTag(STORED_ETAG)
                .contentLength((long) body.length)
                .inputStream(new ByteArrayInputStream(body))
                .build());
    }

    private static OciProperties properties() {
        return new OciProperties(OciAuthMode.SESSION_TOKEN, "dev",
                "MessagesUsers", "sa-saopaulo-1", "test-namespace", "paquetes/", 7,
                "ocid1.compartment.oc1..test");
    }

    private void parSucceeds() {
        when(objectStorage.createPreauthenticatedRequest(any())).thenReturn(CreatePreauthenticatedRequestResponse.builder()
                .preauthenticatedRequest(PreauthenticatedRequest.builder()
                        .id("par-1")
                        .fullPath("https://objectstorage.example/p/TOKEN/o/paquete.json")
                        .timeExpires(Date.from(NOW.plusSeconds(3600)))
                        .build())
                .build());
    }

    private static BmcException bmcException(int status) {
        return new BmcException(status, "Simulated", "simulated OCI error", "opc-request-test");
    }

    private static AssetPackage discordPackage() {
        EnrichedComment post = new EnrichedComment(
                BATCH_ID, "m-1", "channel-1", "author-1", "Autora", "Conseguí mi primer empleo, ¡gracias comunidad!",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", "Primer empleo como dev Java", List.of("#EmpleoTech"), null, false,
                ApprovalStatus.IN_REVIEW, 1, null, null);
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post), NOW);
    }
}
