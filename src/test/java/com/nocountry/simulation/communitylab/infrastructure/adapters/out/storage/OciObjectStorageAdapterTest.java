package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciAuthMode;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.CreatePreauthenticatedRequestResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the OCI Object Storage adapter (Mockito only at the SDK client boundary, no network).
 * Derivado de: spec 006 RF-01 (1 lote = 1 objeto paquetes/{source}/paquete-{batchId}.json, application/json)
 * + RF-03 (1 PAR read por objeto, sin listing) + RF-05 + enmienda 2026-10-10 (412 = sin re-PUT y PAR nueva)
 * + RF-07 (fallo OCI deja el lote pendiente) + RNF-01 (Content-Type; enmienda 2026-10-12: paquete {@code >= 1MB}
 * se sube igual con alarma ERROR, nunca se retiene en Redis) + RNF-03 (TTL {@code PAR_TTL_DAYS})
 * + RNF-06 (logs solo batchId/source/parId, sin contenido, autor ni URL PAR) + plan 006 §3 (PUT ok + PAR
 * fallida = failed, el reintento lo cura).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("OciObjectStorageAdapter")
class OciObjectStorageAdapterTest {

    private static final String BATCH_ID = "batch-1";
    private static final String OBJECT_KEY = "paquetes/discord/paquete-batch-1.json";
    private static final int PAR_TTL_DAYS = 1;
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    // Distinctive values so a leak into the logs cannot be a coincidence.
    private static final String AUTHOR_NAME = "Autora-Privada";
    private static final String MESSAGE = "Conseguí mi primer empleo, ¡gracias comunidad! 🚀";
    private static final String PAR_TOKEN = "SECRET-PAR-TOKEN";
    private static final String PAR_ACCESS_URI = "/p/" + PAR_TOKEN + "/n/test-namespace/b/MessagesUsers/o/" + OBJECT_KEY;
    private static final String PAR_URL = "https://objectstorage.sa-saopaulo-1.oraclecloud.com" + PAR_ACCESS_URI;
    private static final String PAR_ID = "par-id-1";
    // Deliberately not "now + TTL": proves the result carries the expiry OCI confirmed, not the one requested.
    private static final Instant OCI_EXPIRES_AT = Instant.parse("2026-10-09T10:00:00Z");
    // Spec 006 RNF-01 budget per package object (1MB = 1024 * 1024 bytes).
    private static final int ONE_MB = 1_048_576;
    // Same mapper the adapter uses, so the fixture can size a package to the exact byte.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private ObjectStorage objectStorage;

    private OciObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        OciProperties properties = new OciProperties(OciAuthMode.SESSION_TOKEN, "dev",
                "MessagesUsers", "sa-saopaulo-1", "test-namespace", "paquetes/", PAR_TTL_DAYS);
        adapter = new OciObjectStorageAdapter(objectStorage, properties, JSON);
    }

    // ---------- PUT ----------

    @Test
    @DisplayName("Dado un paquete DISCORD, cuando se guarda, entonces hace 1 PUT a paquetes/discord/paquete-{batchId}.json como JSON")
    void putsOneJsonObjectUnderPrefixedKey() {
        // Dado OCI respondiendo 200 al PUT y creando la PAR
        putSucceeds();
        parCreated();

        // Cuando se guarda el paquete
        adapter.save(discordPackage());

        // Entonces 1 PUT con namespace/bucket/key/Content-Type correctos
        PutObjectRequest request = capturedPut();
        assertThat(request.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(request.getBucketName()).isEqualTo("MessagesUsers");
        assertThat(request.getObjectName()).isEqualTo(OBJECT_KEY);
        assertThat(request.getContentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("Dado un PUT, cuando se construye, entonces lleva If-None-Match: * para no sobrescribir un paquete ya guardado")
    void putIsConditionalOnObjectAbsence() {
        // Dado OCI respondiendo 200
        putSucceeds();
        parCreated();

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces la escritura es atomica create-only (RF-05), no exists() + put()
        assertThat(capturedPut().getIfNoneMatch()).isEqualTo("*");
    }

    @Test
    @DisplayName("Dado texto con tildes y emoji, cuando se guarda, entonces contentLength son los bytes UTF-8 del JSON del paquete")
    void bodyIsPackageJsonAndLengthCountsUtf8Bytes() throws Exception {
        // Dado un mensaje con caracteres multi-byte
        putSucceeds();
        parCreated();

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces la longitud declarada coincide con los bytes enviados (no con los caracteres)
        PutObjectRequest request = capturedPut();
        byte[] body = request.getPutObjectBody().readAllBytes();
        assertThat(request.getContentLength()).isEqualTo((long) body.length);
        assertThat(body.length).isGreaterThan(new String(body, StandardCharsets.UTF_8).length());

        // Y el cuerpo es el paquete completo: OCI conserva el autor para trazabilidad (RNF-06)
        JsonNode json = JsonMapper.builder().build().readTree(body);
        assertThat(json.get("batchId").asString()).isEqualTo(BATCH_ID);
        assertThat(json.get("source").asString()).isEqualTo("DISCORD");
        assertThat(json.get("enriched")).hasSize(1);
        assertThat(json.get("enriched").get(0).get("authorName").asString()).isEqualTo(AUTHOR_NAME);
        assertThat(json.get("enriched").get(0).get("messageAuthor").asString()).isEqualTo(MESSAGE);
    }

    // ---------- PAR ----------

    @Test
    @DisplayName("Dado un PUT nuevo, cuando se guarda, entonces responde STORED con la URL PAR para el aviso")
    void newObjectReturnsStoredWithPar() {
        // Dado un objeto que no existía y OCI creando su PAR
        putSucceeds();
        parCreated();

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces el lote se puede descartar y package.completed tiene qué enviar (RF-06)
        assertThat(result.status()).isEqualTo(StoreResult.Status.STORED);
        assertThat(result.ok()).isTrue();
        assertThat(result.deduped()).isFalse();
        assertThat(result.parUrl()).isEqualTo(PAR_URL);
        verify(objectStorage, times(1)).createPreauthenticatedRequest(any());
    }

    @Test
    @DisplayName("Dado un PUT, cuando se crea la PAR, entonces es solo lectura, sobre ese objeto, sin listing y con nombre sin PII")
    void parIsReadOnlyScopedToThePackageObject() {
        // Dado OCI aceptando PUT y PAR
        putSucceeds();
        parCreated();

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces la PAR no abre más que el objeto del lote (RF-03, RF-04)
        CreatePreauthenticatedRequestRequest request = capturedPar();
        CreatePreauthenticatedRequestDetails details = request.getCreatePreauthenticatedRequestDetails();
        assertThat(request.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(request.getBucketName()).isEqualTo("MessagesUsers");
        assertThat(details.getObjectName()).isEqualTo(OBJECT_KEY);
        assertThat(details.getAccessType()).isEqualTo(CreatePreauthenticatedRequestDetails.AccessType.ObjectRead);
        assertThat(details.getBucketListingAction()).isNull();
        assertThat(details.getName()).isEqualTo("par-" + BATCH_ID);
    }

    @Test
    @DisplayName("Dado PAR_TTL_DAYS=1, cuando se crea la PAR, entonces pide caducidad a 1 día desde ahora")
    void parRequestsConfiguredTtl() {
        // Dado OCI aceptando PUT y PAR
        putSucceeds();
        parCreated();

        // Cuando se guarda, acotando el reloj real antes y después
        Instant before = Instant.now();
        adapter.save(discordPackage());
        Instant after = Instant.now();

        // Entonces timeExpires = ahora + TTL configurado (RNF-03), no un valor fijo
        Instant requested = capturedPar().getCreatePreauthenticatedRequestDetails().getTimeExpires().toInstant();
        Duration ttl = Duration.ofDays(PAR_TTL_DAYS);
        // Date has millisecond precision, so truncate the lower bound to compare like with like.
        assertThat(requested).isBetween(before.plus(ttl).truncatedTo(ChronoUnit.MILLIS), after.plus(ttl));
    }

    @Test
    @DisplayName("Dado OCI confirmando una caducidad, cuando se guarda, entonces el resultado lleva la de OCI y no la pedida")
    void resultCarriesExpiryConfirmedByOci() {
        // Dado OCI devolviendo una caducidad distinta de now + TTL
        putSucceeds();
        parCreated();

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces el frontend recibe la verdad de OCI
        assertThat(result.expiresAt()).isEqualTo(OCI_EXPIRES_AT);
    }

    // ---------- idempotency (412) ----------

    @Test
    @DisplayName("Dado un paquete ya subido (412), cuando se re-guarda, entonces no re-sube, crea PAR nueva y responde ALREADY_STORED")
    void preconditionFailedCreatesFreshParWithoutReupload() {
        // Dado OCI rechazando el PUT condicional porque el objeto ya existe
        when(objectStorage.putObject(any())).thenThrow(bmcException(412, "IfNoneMatchFailed"));
        parCreated();

        // Cuando se re-guarda el mismo batchId
        StoreResult result = adapter.save(discordPackage());

        // Entonces éxito idempotente con PAR nueva (enmienda RF-05: OCI no devuelve la URL de una PAR existente)
        assertThat(result.status()).isEqualTo(StoreResult.Status.ALREADY_STORED);
        assertThat(result.ok()).isTrue();
        assertThat(result.deduped()).isTrue();
        assertThat(result.parUrl()).isEqualTo(PAR_URL);
        verify(objectStorage, times(1)).putObject(any());
        verify(objectStorage, times(1)).createPreauthenticatedRequest(any());
    }

    // ---------- failures keep the batch pending ----------

    @Test
    @DisplayName("Dado un error OCI distinto de 412 en el PUT, cuando se guarda, entonces failed y no se pide PAR")
    void putFailureSkipsParAndKeepsBatchPending() {
        // Dado una sesion expirada
        when(objectStorage.putObject(any())).thenThrow(bmcException(401, "NotAuthenticated"));

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces no se da por guardado (RF-07) ni se pide enlace a un objeto que no está
        assertThat(result).isEqualTo(StoreResult.failed());
        verify(objectStorage, never()).createPreauthenticatedRequest(any());
    }

    @Test
    @DisplayName("Dado PUT ok y PAR fallida, cuando se guarda, entonces failed para que el reintento (412) cree la PAR")
    void parFailureAfterPutKeepsBatchPending() {
        // Dado el objeto subido pero sin permiso PAR_MANAGE
        putSucceeds();
        when(objectStorage.createPreauthenticatedRequest(any()))
                .thenThrow(bmcException(404, "NotAuthorizedOrNotFound"));

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces sin PAR no hay aviso: el lote sigue pendiente (plan §3)
        assertThat(result).isEqualTo(StoreResult.failed());
    }

    // ---------- logs (RNF-06) ----------

    @Test
    @DisplayName("Dado exito, cuando se registra, entonces el log tiene batchId/source/etag/parId y nunca autor, contenido ni URL PAR")
    void successLogHasIdsButNoContentNorPar(CapturedOutput output) {
        // Dado OCI respondiendo 200 y creando la PAR
        putSucceeds();
        parCreated();

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces trazable por ids, sin PII ni el token que da acceso al paquete
        assertThat(output).contains("batchId=" + BATCH_ID, "source=DISCORD", "etag=etag-1", "parId=" + PAR_ID);
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE, PAR_TOKEN);
    }

    @Test
    @DisplayName("Dado fallo OCI en el PUT, cuando se registra, entonces el log tiene status/serviceCode y nunca autor ni contenido")
    void putFailureLogHasStatusButNoContent(CapturedOutput output) {
        // Dado bucket inexistente o sin permisos IAM
        when(objectStorage.putObject(any())).thenThrow(bmcException(404, "BucketNotFound"));

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces el log dice que capa falla, sin PII
        assertThat(output).contains("batchId=" + BATCH_ID, "status=404", "serviceCode=BucketNotFound");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE);
    }

    @Test
    @DisplayName("Dado fallo al crear la PAR, cuando se registra, entonces el log la distingue del PUT y no lleva PII")
    void parFailureLogNamesTheParLayer(CapturedOutput output) {
        // Dado el objeto subido pero la PAR rechazada por IAM
        putSucceeds();
        when(objectStorage.createPreauthenticatedRequest(any()))
                .thenThrow(bmcException(404, "NotAuthorizedOrNotFound"));

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces se sabe que falló la PAR (no el PUT) y por qué, sin PII
        assertThat(output).contains("read PAR creation failed", "batchId=" + BATCH_ID,
                "status=404", "serviceCode=NotAuthorizedOrNotFound");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE);
    }

    // ---------- 1MB budget (RNF-01) ----------

    @Test
    @DisplayName("Dado un paquete de exactamente 1MB, cuando se guarda, entonces se sube igual con su PAR y deja una alarma ERROR con ids")
    void packageAtTheBudgetIsUploadedAnywayWithAnAlarm(CapturedOutput output) {
        // Dado un paquete cuyo JSON pesa justo 1048576 bytes (el tope ya es exceso: la regla es <1MB)
        putSucceeds();
        parCreated();

        // Cuando se guarda
        StoreResult result = adapter.save(packageOfExactly(ONE_MB));

        // Entonces no queda retenido en Redis: PUT con todos los bytes, PAR y STORED (habilita discard + aviso)
        assertThat(capturedPut().getContentLength()).isEqualTo((long) ONE_MB);
        assertThat(capturedPar().getCreatePreauthenticatedRequestDetails().getObjectName()).isEqualTo(OBJECT_KEY);
        assertThat(result.status()).isEqualTo(StoreResult.Status.STORED);
        // Y la alarma es ERROR e identifica el lote para localizar el objeto, sin autor ni contenido
        String alarm = output.getAll().lines()
                .filter(line -> line.contains("over the 1MB budget"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 1MB budget alarm was logged"));
        assertThat(alarm).contains("ERROR", "batchId=" + BATCH_ID, "source=DISCORD", "bytes=" + ONE_MB);
        assertThat(output).doesNotContain(AUTHOR_NAME, "a".repeat(64));
    }

    @Test
    @DisplayName("Dado un paquete 1 byte bajo el tope, cuando se guarda, entonces se sube sin alarma")
    void packageJustUnderTheBudgetHasNoAlarm(CapturedOutput output) {
        // Dado un paquete de 1048575 bytes (lote cerrado a 900KB + último mensaje pesado)
        putSucceeds();
        parCreated();

        // Cuando se guarda
        StoreResult result = adapter.save(packageOfExactly(ONE_MB - 1));

        // Entonces es un guardado normal: sin alarma
        assertThat(capturedPut().getContentLength()).isEqualTo(ONE_MB - 1L);
        assertThat(result.status()).isEqualTo(StoreResult.Status.STORED);
        assertThat(output).doesNotContain("over the 1MB budget");
    }

    // ---------- fixtures ----------

    private void putSucceeds() {
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("etag-1").build());
    }

    private void parCreated() {
        PreauthenticatedRequest par = PreauthenticatedRequest.builder()
                .id(PAR_ID)
                .name("par-" + BATCH_ID)
                .objectName(OBJECT_KEY)
                .accessUri(PAR_ACCESS_URI)
                .fullPath(PAR_URL)
                .timeExpires(Date.from(OCI_EXPIRES_AT))
                .build();
        when(objectStorage.createPreauthenticatedRequest(any()))
                .thenReturn(CreatePreauthenticatedRequestResponse.builder().preauthenticatedRequest(par).build());
    }

    private PutObjectRequest capturedPut() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(objectStorage).putObject(captor.capture());
        return captor.getValue();
    }

    private CreatePreauthenticatedRequestRequest capturedPar() {
        ArgumentCaptor<CreatePreauthenticatedRequestRequest> captor =
                ArgumentCaptor.forClass(CreatePreauthenticatedRequestRequest.class);
        verify(objectStorage).createPreauthenticatedRequest(captor.capture());
        return captor.getValue();
    }

    private static BmcException bmcException(int status, String serviceCode) {
        return new BmcException(status, serviceCode, "simulated OCI error", "opc-request-test");
    }

    // Why pad messageAuthor: it is the only free-length field (post fields are capped per channel) and
    // ASCII adds exactly 1 byte per char, so the serialized package can be sized to the byte.
    private static AssetPackage packageOfExactly(int targetBytes) {
        int baseBytes = JSON.writeValueAsBytes(packageWithAuthorText("")).length;
        AssetPackage sized = packageWithAuthorText("a".repeat(targetBytes - baseBytes));
        assertThat(JSON.writeValueAsBytes(sized)).hasSize(targetBytes);
        return sized;
    }

    private static AssetPackage discordPackage() {
        return packageWithAuthorText(MESSAGE);
    }

    private static AssetPackage packageWithAuthorText(String messageAuthor) {
        EnrichedComment post = new EnrichedComment(
                BATCH_ID, "m-1", "channel-1", "author-1", AUTHOR_NAME, messageAuthor,
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", "Primer empleo como dev Java", List.of("#EmpleoTech"), null, false, 1);
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post), NOW);
    }
}
