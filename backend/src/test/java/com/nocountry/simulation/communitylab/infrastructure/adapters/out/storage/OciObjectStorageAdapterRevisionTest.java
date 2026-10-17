package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciAuthMode;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Revision side of the OCI adapter: read a stored package with its etag and rewrite it only if
 * nobody wrote in between (Mockito only at the SDK client, no network).
 *
 * <p>Derivado de: spec 007 RF-05 (reescribe el paquete completo; OCI conserva la versión anterior) +
 * RF-06 ({@code If-Match: etag}, {@code 412} → reintento) + RF-07 (sin PAR nueva por revisión) + RF-09
 * (fallo OCI distinto de {@code 404/412} → {@code 502}) + RNF-01 (paquete {@code >= 1MB} al leer o
 * reescribir → {@code 413} sin escribir) + RNF-04 (logs sin contenido, autor ni etag) + RF-01
 * (paquete anterior a 007 se lee como {@code approved=false, versionMessage=1}) + plan 007 §1.2.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("OciObjectStorageAdapter (revision)")
class OciObjectStorageAdapterRevisionTest {

    private static final String BATCH_ID = "batch-1";
    private static final String DISCORD_KEY = "paquetes/discord/paquete-batch-1.json";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    // Distinctive values so a leak into the logs cannot be a coincidence.
    private static final String AUTHOR_NAME = "Autora-Privada";
    private static final String MESSAGE = "Conseguí mi primer empleo, ¡gracias comunidad! 🚀";
    private static final String ETAG = "ETAG-SECRET-1";
    private static final int ONE_MB = 1_048_576;
    // Same mapper the adapter uses, so the fixtures serialize exactly as the bucket holds them.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private ObjectStorage objectStorage;

    private OciObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        OciProperties properties = new OciProperties(OciAuthMode.SESSION_TOKEN, "dev",
                "MessagesUsers", "sa-saopaulo-1", "test-namespace", "paquetes/", 1,
                "ocid1.compartment.oc1..test");
        adapter = new OciObjectStorageAdapter(objectStorage, properties, JSON);
    }

    // ================= load: GetObject =================

    @Test
    @DisplayName("Dado un lote DISCORD, cuando se carga, entonces hace GetObject de paquetes/discord/paquete-{batchId}.json")
    void loadReadsTheSameObjectSaveWrote() {
        // Dado el objeto guardado por el flush de 006
        objectHolds(json(discordPackage()));

        // Cuando se carga para revisar
        adapter.load(Source.DISCORD, BATCH_ID);

        // Entonces lee el mismo nombre que save escribió (si difiriera, toda revisión daría 404)
        GetObjectRequest request = capturedGet();
        assertThat(request.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(request.getBucketName()).isEqualTo("MessagesUsers");
        assertThat(request.getObjectName()).isEqualTo(DISCORD_KEY);
    }

    @Test
    @DisplayName("Dado un lote TELEGRAM, cuando se carga, entonces lee de la carpeta telegram")
    void loadUsesTheSourceFolder() {
        // Dado un paquete de Telegram
        objectHolds(json(packageOf(Source.TELEGRAM, post(MESSAGE))));

        // Cuando se carga
        adapter.load(Source.TELEGRAM, BATCH_ID);

        // Entonces nunca mezcla carpetas de fuentes
        assertThat(capturedGet().getObjectName()).isEqualTo("paquetes/telegram/paquete-batch-1.json");
    }

    @Test
    @DisplayName("Dado un objeto guardado, cuando se carga, entonces devuelve el mismo paquete y el etag de esa lectura")
    void loadReturnsThePackageAndItsEtag() {
        // Dado el paquete en OCI
        AssetPackage original = discordPackage();
        objectHolds(json(original));

        // Cuando se carga
        Optional<StoredPackage> stored = adapter.load(Source.DISCORD, BATCH_ID);

        // Entonces el paquete vuelve intacto y el etag es el de la respuesta (para el If-Match)
        assertThat(stored).isPresent();
        assertThat(stored.get().pkg()).isEqualTo(original);
        assertThat(stored.get().etag()).isEqualTo(ETAG);
    }

    @Test
    @DisplayName("Dado un paquete guardado antes de 007 (sin revised, approved ni versionMessage), cuando se carga, entonces sale false/IN_REVIEW/1")
    void loadReadsPackagesWrittenBeforeRevisions() {
        // Dado el JSON de un paquete sin los campos de revisión
        ObjectNode tree = (ObjectNode) JSON.readTree(json(discordPackage()));
        ObjectNode record = (ObjectNode) tree.get("enriched").get(0);
        record.remove("revised");
        record.remove("approved");
        record.remove("versionMessage");
        objectHolds(JSON.writeValueAsBytes(tree));

        // Cuando se carga
        EnrichedComment post = adapter.load(Source.DISCORD, BATCH_ID).orElseThrow().pkg().enriched().get(0);

        // Entonces se puede revisar sin migración (RF-01)
        assertThat(post.revised()).isFalse();
        assertThat(post.approved()).isEqualTo(ApprovalStatus.IN_REVIEW);
        assertThat(post.versionMessage()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un batchId sin objeto (404), cuando se carga, entonces devuelve vacío")
    void missingObjectIsEmpty() {
        // Dado que OCI no tiene el objeto
        when(objectStorage.getObject(any())).thenThrow(bmcException(404, "ObjectNotFound"));

        // Cuando se carga / Entonces vacío: el servicio responde 404 sin escribir
        assertThat(adapter.load(Source.DISCORD, BATCH_ID)).isEmpty();
    }

    @Test
    @DisplayName("Dado un objeto de 1MB o más, cuando se carga, entonces se rechaza por tamaño sin revisarlo")
    void objectAtTheBudgetIsTooLarge() {
        // Dado un objeto que ya mide el tope (solo posible por un bug: 006 lo sube igual con alarma)
        when(objectStorage.getObject(any())).thenReturn(GetObjectResponse.builder()
                .eTag(ETAG)
                .contentLength((long) ONE_MB)
                .inputStream(new ByteArrayInputStream(new byte[0]))
                .build());

        // Cuando se carga / Entonces 413: ni se lee el cuerpo ni se podrá reescribir
        assertThatThrownBy(() -> adapter.load(Source.DISCORD, BATCH_ID))
                .isInstanceOf(PackageTooLargeException.class);
    }

    @Test
    @DisplayName("Dado un objeto 1 byte bajo el tope, cuando se carga, entonces se lee normalmente")
    void objectJustUnderTheBudgetLoads() {
        // Dado un paquete de 1048575 bytes
        objectHolds(json(packageOfExactly(ONE_MB - 1)));

        // Cuando se carga / Entonces está disponible para revisar
        assertThat(adapter.load(Source.DISCORD, BATCH_ID)).isPresent();
    }

    @Test
    @DisplayName("Dado un error OCI distinto de 404, cuando se carga, entonces error de almacenamiento con status en el log")
    void otherReadFailuresAreStorageErrors(CapturedOutput output) {
        // Dado una sesión expirada
        when(objectStorage.getObject(any())).thenThrow(bmcException(401, "NotAuthenticated"));

        // Cuando se carga / Entonces 502, nunca "no existe" (que llevaría al usuario a un 404 falso)
        assertThatThrownBy(() -> adapter.load(Source.DISCORD, BATCH_ID))
                .isInstanceOf(StorageFailureException.class);
        assertThat(output).contains("batchId=" + BATCH_ID, "status=401", "serviceCode=NotAuthenticated");
    }

    @Test
    @DisplayName("Dado un objeto con JSON corrupto, cuando se carga, entonces error de almacenamiento")
    void corruptObjectIsAStorageError() {
        // Dado un cuerpo que no es un paquete
        objectHolds("{not a package".getBytes(StandardCharsets.UTF_8));

        // Cuando se carga / Entonces 502: no se puede revisar lo que no se puede leer
        assertThatThrownBy(() -> adapter.load(Source.DISCORD, BATCH_ID))
                .isInstanceOf(StorageFailureException.class);
    }

    @Test
    @DisplayName("Dado una carga, cuando se registra, entonces el log tiene ids y nunca autor, contenido ni etag")
    void loadLogHasIdsButNoContentNorEtag(CapturedOutput output) {
        // Dado el paquete en OCI
        objectHolds(json(discordPackage()));

        // Cuando se carga
        adapter.load(Source.DISCORD, BATCH_ID);

        // Entonces RNF-04
        assertThat(output).contains("batchId=" + BATCH_ID, "source=DISCORD");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE, ETAG);
    }

    // ================= replace: PutObject If-Match =================

    @Test
    @DisplayName("Dado un paquete revisado, cuando se reescribe, entonces PUT al mismo objeto con If-Match del etag leído")
    void replaceIsConditionalOnTheEtagRead() {
        // Dado OCI aceptando la escritura
        putSucceeds();

        // Cuando se reescribe con el etag de la lectura
        adapter.replace(revisedPackage(), ETAG);

        // Entonces solo escribe si nadie cambió el objeto desde la lectura (RF-06)
        PutObjectRequest request = capturedPut();
        assertThat(request.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(request.getBucketName()).isEqualTo("MessagesUsers");
        assertThat(request.getObjectName()).isEqualTo(DISCORD_KEY);
        assertThat(request.getIfMatch()).isEqualTo(ETAG);
        assertThat(request.getIfNoneMatch()).isNull();
        assertThat(request.getContentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("Dado un paquete revisado, cuando se reescribe, entonces el cuerpo es el paquete completo con la revisión")
    void replaceBodyIsTheWholeRevisedPackage() throws Exception {
        // Dado OCI aceptando la escritura
        putSucceeds();

        // Cuando se reescribe
        adapter.replace(revisedPackage(), ETAG);

        // Entonces OCI recibe el paquete entero (no se puede editar una parte del objeto) y contentLength = bytes UTF-8
        PutObjectRequest request = capturedPut();
        byte[] body = request.getPutObjectBody().readAllBytes();
        assertThat(request.getContentLength()).isEqualTo((long) body.length);
        JsonNode record = JSON.readTree(body).get("enriched").get(0);
        assertThat(record.get("approved").asString()).isEqualTo("APPROVED");
        // The amendment fields travel in the stored object even when unset (spec 007 RF-01, 2026-10-16)
        assertThat(record.get("reject").asBoolean()).isFalse();
        assertThat(record.has("publishedAt")).isTrue();
        assertThat(record.get("publishedAt").isNull()).isTrue();
        assertThat(record.get("versionMessage").asInt()).isEqualTo(2);
        assertThat(record.get("authorName").asString()).isEqualTo(AUTHOR_NAME);
    }

    @Test
    @DisplayName("Dado OCI aceptando la escritura, cuando se reescribe, entonces REPLACED y no se crea PAR nueva")
    void successfulWriteIsReplacedWithoutNewPar() {
        // Dado OCI respondiendo 200
        putSucceeds();

        // Cuando se reescribe
        ReplaceResult result = adapter.replace(revisedPackage(), ETAG);

        // Entonces la PAR del lote sigue sirviendo la última versión (RF-07)
        assertThat(result).isEqualTo(ReplaceResult.REPLACED);
        verify(objectStorage, never()).createPreauthenticatedRequest(any());
    }

    @Test
    @DisplayName("Dado otro cambio entre lectura y escritura (412), cuando se reescribe, entonces CONFLICT")
    void preconditionFailedIsAConflict() {
        // Dado que el etag ya no es el actual
        when(objectStorage.putObject(any())).thenThrow(bmcException(412, "IfMatchFailed"));

        // Cuando se reescribe / Entonces el servicio relee y reintenta
        assertThat(adapter.replace(revisedPackage(), ETAG)).isEqualTo(ReplaceResult.CONFLICT);
    }

    @Test
    @DisplayName("Dado un error OCI distinto de 412, cuando se reescribe, entonces FAILED con status en el log y sin etag")
    void otherWriteFailuresAreFailed(CapturedOutput output) {
        // Dado un fallo del servicio
        when(objectStorage.putObject(any())).thenThrow(bmcException(500, "InternalServerError"));

        // Cuando se reescribe
        ReplaceResult result = adapter.replace(revisedPackage(), ETAG);

        // Entonces 502 en el servicio; el log dice qué falló sin PII ni etag (RNF-04)
        assertThat(result).isEqualTo(ReplaceResult.FAILED);
        assertThat(output).contains("batchId=" + BATCH_ID, "status=500", "serviceCode=InternalServerError");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE, ETAG);
    }

    @Test
    @DisplayName("Dado un paquete revisado de 1MB o más, cuando se reescribe, entonces se rechaza por tamaño sin PUT")
    void revisedPackageAtTheBudgetIsNotWritten() {
        // Dado una revisión que deja el paquete en el tope
        AssetPackage tooLarge = packageOfExactly(ONE_MB);

        // Cuando se reescribe / Entonces 413 y OCI no cambia (aquí no hay Redis que retener)
        assertThatThrownBy(() -> adapter.replace(tooLarge, ETAG))
                .isInstanceOf(PackageTooLargeException.class);
        verify(objectStorage, never()).putObject(any());
    }

    @Test
    @DisplayName("Dado una reescritura correcta, cuando se registra, entonces el log tiene ids y nunca autor, contenido ni etag")
    void replaceLogHasIdsButNoContentNorEtag(CapturedOutput output) {
        // Dado OCI respondiendo 200 con un etag nuevo
        putSucceeds();

        // Cuando se reescribe
        adapter.replace(revisedPackage(), ETAG);

        // Entonces RNF-04
        assertThat(output).contains("batchId=" + BATCH_ID, "source=DISCORD");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE, ETAG, "ETAG-SECRET-2");
    }

    // ================= fixtures =================

    private void objectHolds(byte[] body) {
        when(objectStorage.getObject(any())).thenReturn(GetObjectResponse.builder()
                .eTag(ETAG)
                .contentLength((long) body.length)
                .inputStream(new ByteArrayInputStream(body))
                .build());
    }

    private void putSucceeds() {
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("ETAG-SECRET-2").build());
    }

    private GetObjectRequest capturedGet() {
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(objectStorage).getObject(captor.capture());
        return captor.getValue();
    }

    private PutObjectRequest capturedPut() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(objectStorage).putObject(captor.capture());
        return captor.getValue();
    }

    private static BmcException bmcException(int status, String serviceCode) {
        return new BmcException(status, serviceCode, "simulated OCI error", "opc-request-test");
    }

    private static byte[] json(AssetPackage pkg) {
        return JSON.writeValueAsBytes(pkg);
    }

    private static EnrichedComment post(String messageAuthor) {
        return new EnrichedComment(
                BATCH_ID, "m-1", "channel-1", "author-1", AUTHOR_NAME, messageAuthor,
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", "Primer empleo como dev Java", List.of("#EmpleoTech"), null, false, ApprovalStatus.IN_REVIEW, 1, null, null);
    }

    private static AssetPackage packageOf(Source source, EnrichedComment record) {
        return AssetPackage.of(BATCH_ID, source, List.of(record), NOW);
    }

    private static AssetPackage discordPackage() {
        return packageOf(Source.DISCORD, post(MESSAGE));
    }

    private static AssetPackage revisedPackage() {
        return discordPackage().revise("m-1", new PostChange(null, ApprovalStatus.APPROVED, null, null, null, null, null, null, null));
    }

    // Why pad messageAuthor: it is the only free-length field (post fields are capped per channel) and
    // ASCII adds exactly 1 byte per char, so the serialized package can be sized to the byte.
    private static AssetPackage packageOfExactly(int targetBytes) {
        int baseBytes = json(packageOf(Source.DISCORD, post(""))).length;
        AssetPackage sized = packageOf(Source.DISCORD, post("a".repeat(targetBytes - baseBytes)));
        assertThat(json(sized)).hasSize(targetBytes);
        return sized;
    }
}
