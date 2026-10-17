package com.nocountry.simulation.communitylab.infrastructure.config.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.infrastructure.dto.revision.RevisePostRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reading stored records with the {@code ObjectMapper} Spring Boot builds (the one Redis and OCI adapters get):
 * the {@code @JacksonMixin} must translate the boolean {@code approved} written before the enum, and only for
 * {@code EnrichedComment}. Spring context limited to Jackson ({@code @JsonTest}) because the registration is
 * exactly what is under test.
 *
 * <p>Derivado de: spec 007 RF-01 (enmienda 2026-10-16: compatibilidad sin migración) + RF-02 (el {@code PATCH}
 * rechaza el boolean) + plan 007 §1.4, §3, §4.
 */
@JsonTest
@DisplayName("Legacy approved in stored records")
class LegacyApprovalJsonTest {

    private static final Instant SENT = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-10-16T15:00:00Z");

    // Why: the main class enables TelegramBotProperties, which binds a long at startup.
    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot.listen-group-id", () -> "555");
    }

    @Autowired
    private ObjectMapper objectMapper;

    private static EnrichedComment post(String messageId, ApprovalStatus approved, Instant publishedAt) {
        return new EnrichedComment(
                "b-1", messageId, "channel-1", "author-1", "author-name",
                "Conseguí empleo como dev Java", Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo"), 75, null, EnrichedComment.PROMPT_VERSION, SENT, Source.DISCORD,
                Channels.X, null, "Nuevo empleo en Java", List.of("#Java"), null,
                true, approved, 2, null, publishedAt);
    }

    // A record as it was stored before the enum: boolean approved, no reject nor publishedAt.
    private ObjectNode legacy(String messageId, boolean approved, boolean revised) {
        ObjectNode node = (ObjectNode) objectMapper.valueToTree(post(messageId, ApprovalStatus.IN_REVIEW, null));
        node.put("approved", approved);
        node.put("revised", revised);
        node.remove("reject");
        node.remove("publishedAt");
        return node;
    }

    private EnrichedComment read(ObjectNode node) {
        return objectMapper.readValue(node.toString(), EnrichedComment.class);
    }

    @Test
    @DisplayName("Dado un registro guardado con approved=true, cuando se lee, entonces queda APPROVED")
    void legacyTrueReadsApproved() {
        // Dado / Cuando
        EnrichedComment post = read(legacy("m-1", true, true));

        // Entonces
        assertThat(post.approved()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(post.reject()).isFalse();
    }

    @Test
    @DisplayName("Dado un registro revisado con approved=false, cuando se lee, entonces queda REJECTED con reject=true")
    void legacyFalseReviewedReadsRejected() {
        // Dado "revisado y no apto" en el formato anterior
        EnrichedComment post = read(legacy("m-1", false, true));

        // Entonces el rechazo implícito pasa a ser explícito
        assertThat(post.approved()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(post.reject()).isTrue();
    }

    @Test
    @DisplayName("Dado un registro sin revisar con approved=false, cuando se lee, entonces queda IN_REVIEW")
    void legacyFalseNotReviewedReadsInReview() {
        // Dado / Cuando
        EnrichedComment post = read(legacy("m-1", false, false));

        // Entonces
        assertThat(post.approved()).isEqualTo(ApprovalStatus.IN_REVIEW);
        assertThat(post.reject()).isFalse();
    }

    @Test
    @DisplayName("Dado un registro anterior a spec 007 (sin approved), cuando se lee, entonces queda IN_REVIEW sin publicar")
    void recordWithoutApprovedReadsInReview() {
        // Dado un registro sin ningún campo de revisión
        ObjectNode node = legacy("m-1", false, false);
        node.remove("approved");
        node.remove("revised");

        // Cuando se lee
        EnrichedComment post = read(node);

        // Entonces no se descarta y tiene los valores por defecto
        assertThat(post.approved()).isEqualTo(ApprovalStatus.IN_REVIEW);
        assertThat(post.reject()).isFalse();
        assertThat(post.publishedAt()).isNull();
    }

    @Test
    @DisplayName("Dado un registro con el estado y publishedAt, cuando se lee, entonces vuelve igual")
    void currentFormatRoundTrips() {
        // Dado un post aprobado y publicado escrito con el formato nuevo
        EnrichedComment original = post("m-1", ApprovalStatus.APPROVED, PUBLISHED);

        // Cuando se escribe y se vuelve a leer
        EnrichedComment read = objectMapper.readValue(objectMapper.writeValueAsString(original), EnrichedComment.class);

        // Entonces es el mismo registro
        assertThat(read).isEqualTo(original);
    }

    @Test
    @DisplayName("Dado un registro nuevo, cuando se escribe, entonces el JSON lleva el estado por nombre, reject y publishedAt ISO-8601")
    void writesTheNewContract() {
        // Dado un post rechazado y otro aprobado y publicado
        ObjectNode rejected = (ObjectNode) objectMapper.valueToTree(post("m-1", ApprovalStatus.REJECTED, null));
        ObjectNode published = (ObjectNode) objectMapper.valueToTree(post("m-2", ApprovalStatus.APPROVED, PUBLISHED));

        // Entonces es lo que el frontend lee por la PAR
        assertThat(rejected.get("approved").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("reject").asBoolean()).isTrue();
        assertThat(rejected.get("publishedAt").isNull()).isTrue();
        assertThat(published.get("approved").asString()).isEqualTo("APPROVED");
        assertThat(published.get("reject").asBoolean()).isFalse();
        assertThat(published.get("publishedAt").asString()).isEqualTo("2026-10-16T15:00:00Z");
    }

    @Test
    @DisplayName("Dado un estado desconocido en un registro guardado, cuando se lee, entonces falla como un JSON corrupto")
    void unknownStateFails() {
        // Dado un valor fuera del enum
        ObjectNode node = legacy("m-1", false, false);
        node.put("approved", "PENDING");

        // Cuando / Entonces el adaptador lo trata como ilegible (Redis lo salta, OCI responde 502)
        assertThatThrownBy(() -> read(node)).isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("Dado un paquete OCI con registros en el formato anterior, cuando se lee, entonces cada post se traduce")
    void packageWithLegacyRecordsIsTranslated() {
        // Dado un paquete guardado antes del enum, con un post aprobado y otro rechazado
        ObjectNode pkg = (ObjectNode) objectMapper.valueToTree(AssetPackage.of(
                "b-1", Source.DISCORD, List.of(post("m-1", ApprovalStatus.IN_REVIEW, null),
                        post("m-2", ApprovalStatus.IN_REVIEW, null)), SENT));
        pkg.putArray("enriched").add(legacy("m-1", true, true)).add(legacy("m-2", false, true));

        // Cuando se lee como lo hace el adaptador de OCI
        AssetPackage read = objectMapper.readValue(pkg.toString(), AssetPackage.class);

        // Entonces el mixin también aplica a los registros anidados
        assertThat(read.enriched()).extracting(EnrichedComment::approved)
                .containsExactly(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("Dado un body de PATCH con approved=true, cuando se lee, entonces falla (el mixin solo aplica a registros guardados)")
    void patchBodyStaysStrict() {
        // Dado el contrato anterior en el body del PATCH
        String body = "{\"expectedVersion\":1,\"approved\":true}";

        // Cuando / Entonces no se acepta en silencio
        assertThatThrownBy(() -> objectMapper.readValue(body, RevisePostRequest.class))
                .isInstanceOf(JacksonException.class);
    }
}
