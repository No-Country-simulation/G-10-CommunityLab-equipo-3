package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the OCI Object Storage adapter (Mockito only at the SDK client boundary, no network).
 * Derivado de: spec 006 RF-01 (1 lote = 1 objeto paquetes/{source}/paquete-{batchId}.json, application/json)
 * + RF-05 (re-flush mismo batchId no duplica: deduped) + RF-07 (fallo OCI deja el lote pendiente)
 * + RNF-01 (Content-Type) + RNF-06 (logs solo batchId/source, sin contenido ni autor).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("OciObjectStorageAdapter")
class OciObjectStorageAdapterTest {

    private static final String BATCH_ID = "batch-1";
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    // Distinctive values so a leak into the logs cannot be a coincidence.
    private static final String AUTHOR_NAME = "Autora-Privada";
    private static final String MESSAGE = "Conseguí mi primer empleo, ¡gracias comunidad! 🚀";

    @Mock
    private ObjectStorage objectStorage;

    private OciObjectStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        OciProperties properties = new OciProperties(OciAuthMode.SESSION_TOKEN, "dev",
                "MessagesUsers", "sa-saopaulo-1", "test-namespace", "paquetes/", 7);
        adapter = new OciObjectStorageAdapter(objectStorage, properties, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("Dado un paquete DISCORD, cuando se guarda, entonces hace 1 PUT a paquetes/discord/paquete-{batchId}.json como JSON")
    void putsOneJsonObjectUnderPrefixedKey() {
        // Dado OCI respondiendo 200
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        // Cuando se guarda el paquete
        StoreResult result = adapter.save(discordPackage());

        // Entonces 1 PUT con namespace/bucket/key/Content-Type correctos y resultado guardado
        PutObjectRequest request = capturedRequest();
        assertThat(request.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(request.getBucketName()).isEqualTo("MessagesUsers");
        assertThat(request.getObjectName()).isEqualTo("paquetes/discord/paquete-batch-1.json");
        assertThat(request.getContentType()).isEqualTo("application/json");
        assertThat(result).isEqualTo(StoreResult.stored());
    }

    @Test
    @DisplayName("Dado un PUT, cuando se construye, entonces lleva If-None-Match: * para no sobrescribir un paquete ya guardado")
    void putIsConditionalOnObjectAbsence() {
        // Dado OCI respondiendo 200
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces la escritura es atomica create-only (RF-05), no exists() + put()
        assertThat(capturedRequest().getIfNoneMatch()).isEqualTo("*");
    }

    @Test
    @DisplayName("Dado texto con tildes y emoji, cuando se guarda, entonces contentLength son los bytes UTF-8 del JSON del paquete")
    void bodyIsPackageJsonAndLengthCountsUtf8Bytes() throws Exception {
        // Dado un mensaje con caracteres multi-byte
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces la longitud declarada coincide con los bytes enviados (no con los caracteres)
        PutObjectRequest request = capturedRequest();
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

    @Test
    @DisplayName("Dado un paquete ya subido (412), cuando se re-guarda, entonces responde ok + deduped para que el lote se descarte")
    void preconditionFailedMeansAlreadyStored() {
        // Dado OCI rechazando el PUT condicional porque el objeto ya existe
        when(objectStorage.putObject(any())).thenThrow(bmcException(412, "IfNoneMatchFailed"));

        // Cuando se re-guarda el mismo batchId
        StoreResult result = adapter.save(discordPackage());

        // Entonces es un exito idempotente, no un fallo (evita el lote zombi en pending)
        assertThat(result).isEqualTo(StoreResult.alreadyStored());
        assertThat(result.ok()).isTrue();
        assertThat(result.deduped()).isTrue();
    }

    @Test
    @DisplayName("Dado un error OCI distinto de 412, cuando se guarda, entonces responde failed para que el lote siga pendiente")
    void otherOciErrorsKeepBatchPending() {
        // Dado una sesion expirada
        when(objectStorage.putObject(any())).thenThrow(bmcException(401, "NotAuthenticated"));

        // Cuando se guarda
        StoreResult result = adapter.save(discordPackage());

        // Entonces no se da por guardado (RF-07: se reintenta en el siguiente tick)
        assertThat(result).isEqualTo(StoreResult.failed());
    }

    @Test
    @DisplayName("Dado exito, cuando se registra, entonces el log tiene batchId/source/etag y nunca autor ni contenido")
    void successLogHasIdsButNoContent(CapturedOutput output) {
        // Dado OCI respondiendo 200
        when(objectStorage.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces trazable por ids, sin PII
        assertThat(output).contains("batchId=" + BATCH_ID, "source=DISCORD", "etag=etag-1");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE);
    }

    @Test
    @DisplayName("Dado fallo OCI, cuando se registra, entonces el log tiene status/serviceCode y nunca autor ni contenido")
    void failureLogHasStatusButNoContent(CapturedOutput output) {
        // Dado bucket inexistente o sin permisos IAM
        when(objectStorage.putObject(any())).thenThrow(bmcException(404, "BucketNotFound"));

        // Cuando se guarda
        adapter.save(discordPackage());

        // Entonces el log dice que capa falla, sin PII
        assertThat(output).contains("batchId=" + BATCH_ID, "status=404", "serviceCode=BucketNotFound");
        assertThat(output).doesNotContain(AUTHOR_NAME, MESSAGE);
    }

    private PutObjectRequest capturedRequest() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(objectStorage).putObject(captor.capture());
        return captor.getValue();
    }

    private static BmcException bmcException(int status, String serviceCode) {
        return new BmcException(status, serviceCode, "simulated OCI error", "opc-request-test");
    }

    private static AssetPackage discordPackage() {
        EnrichedComment post = new EnrichedComment(
                BATCH_ID, "m-1", "channel-1", "author-1", AUTHOR_NAME, MESSAGE,
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 80, null, EnrichedComment.PROMPT_VERSION, NOW, Source.DISCORD,
                Channels.FAQ, "Primer empleo dev", "Primer empleo como dev Java", List.of("#EmpleoTech"), null, false, 1);
        return AssetPackage.of(BATCH_ID, Source.DISCORD, List.of(post), NOW);
    }
}
