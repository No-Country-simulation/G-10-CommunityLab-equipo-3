package com.nocountry.simulation.communitylab.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;

/**
 * Unit tests for revising one post (no Spring): approval, edition, version bump and channel rules.
 *
 * <p>Derivado de: spec 007 RF-02 (ausente = no cambia, {@code ""}/{@code []} = vaciar) + RF-03
 * (reglas de canal sobre el {@code channelPost} final, {@code LLM_FALLBACK} no revisable) + RF-04
 * (toda actualización guardada sube {@code versionMessage}, editar conserva {@code approved},
 * {@code messageId} nunca cambia, no-op sin cambio; enmienda 2026-10-13: toda revisión guardada deja
 * {@code revised=true}, {@code revised:true} solo marca revisado) + plan 007 §1.2, §1.3 (domain), §3.
 */
@DisplayName("EnrichedComment.revise")
class EnrichedCommentReviseTest {

    private static final Instant SENT = Instant.parse("2026-10-12T10:00:00Z");
    private static final String LINKEDIN_COPY = "a".repeat(100);
    private static final String EDITED_COPY = "b".repeat(120);
    private static final String CTA = "Comparte tu historia";

    private static EnrichedComment post(Channels channel, String title, String copy, List<String> hashtags,
                                        String cta, Boolean approved, Integer versionMessage, String flag) {
        return post(channel, title, copy, hashtags, cta, false, approved, versionMessage, flag);
    }

    private static EnrichedComment post(Channels channel, String title, String copy, List<String> hashtags,
                                        String cta, Boolean revised, Boolean approved, Integer versionMessage,
                                        String flag) {
        return new EnrichedComment(
                "batch-1", "msg-1", "channel-1", "author-1", "author-name",
                "Conseguí empleo como dev Java", Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo", "java"), 75, flag, EnrichedComment.PROMPT_VERSION,
                SENT, Source.DISCORD,
                channel, title, copy, hashtags, cta, revised, approved, versionMessage);
    }

    private static EnrichedComment linkedin(boolean approved, int versionMessage) {
        return post(Channels.LINKEDIN, null, LINKEDIN_COPY, List.of("#ONE", "#Java"), CTA,
                approved, versionMessage, null);
    }

    private static EnrichedComment fallback() {
        return post(Channels.FAQ, "", "", List.of(), "", false, 1, EnrichedComment.LLM_FALLBACK);
    }

    private static EnrichedComment linkedinReviewed(boolean revised, int versionMessage) {
        return post(Channels.LINKEDIN, null, LINKEDIN_COPY, List.of("#ONE", "#Java"), CTA,
                revised, false, versionMessage, null);
    }

    private static PostChange markReviewed() {
        return new PostChange(true, null, null, null, null, null, null, null);
    }

    private static PostChange approve(boolean approved) {
        return new PostChange(null, approved, null, null, null, null, null, null);
    }

    private static PostChange editCopy(String copy) {
        return new PostChange(null, null, null, null, null, copy, null, null);
    }

    // ---------- RF-04: every saved update bumps the version ----------

    @Test
    @DisplayName("Dado un post sin aprobar en v1, cuando se aprueba, entonces queda aprobado en v2 con el mismo contenido")
    void approvingBumpsVersionAndKeepsContent() {
        // Dado un post recién salido de la IA
        EnrichedComment original = linkedin(false, 1);

        // Cuando se aprueba
        EnrichedComment revised = original.revise(approve(true));

        // Entonces sube la versión, cambia solo approved y el post es el mismo
        assertThat(revised.approved()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(2);
        assertThat(revised.channelPost()).isEqualTo(original.channelPost());
        assertThat(revised.titlePost()).isEqualTo(original.titlePost());
        assertThat(revised.outputContentProcessed()).isEqualTo(original.outputContentProcessed());
        assertThat(revised.hashtags()).isEqualTo(original.hashtags());
        assertThat(revised.cta()).isEqualTo(original.cta());
        assertThat(revised.topics()).isEqualTo(original.topics());
    }

    @Test
    @DisplayName("Dado un post aprobado, cuando se edita el texto sin enviar approved, entonces sigue aprobado y sube la versión")
    void editingKeepsApproval() {
        // Dado un post ya aprobado
        EnrichedComment original = linkedin(true, 1);

        // Cuando se edita solo el texto
        EnrichedComment revised = original.revise(editCopy(EDITED_COPY));

        // Entonces la aprobación es una marca del usuario que su propia edición no invalida
        assertThat(revised.approved()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(2);
        assertThat(revised.outputContentProcessed()).isEqualTo(EDITED_COPY);
    }

    @Test
    @DisplayName("Dado un post aprobado, cuando se desaprueba, entonces queda sin aprobar y sube la versión")
    void unapprovingBumpsVersion() {
        // Dado un post aprobado
        EnrichedComment original = linkedin(true, 1);

        // Cuando se desaprueba
        EnrichedComment revised = original.revise(approve(false));

        // Entonces desaprobar también es una actualización guardada
        assertThat(revised.approved()).isFalse();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post aprobado, cuando la misma petición edita y envía approved=false, entonces gana el valor enviado")
    void explicitApprovalInTheSameRequestWins() {
        // Dado un post aprobado
        EnrichedComment original = linkedin(true, 1);

        // Cuando se edita y se desaprueba a la vez
        EnrichedComment revised = original.revise(
                new PostChange(null, false, null, null, null, EDITED_COPY, null, null));

        // Entonces approved es el enviado y la versión sube una sola vez
        assertThat(revised.approved()).isFalse();
        assertThat(revised.outputContentProcessed()).isEqualTo(EDITED_COPY);
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post en v3, cuando se revisa, entonces pasa a v4 (sube desde la versión actual)")
    void bumpsFromTheCurrentVersion() {
        // Dado un post ya revisado dos veces
        EnrichedComment original = linkedin(false, 3);

        // Cuando se aprueba
        EnrichedComment revised = original.revise(approve(true));

        // Entonces el historial no repite números
        assertThat(revised.versionMessage()).isEqualTo(4);
    }

    @Test
    @DisplayName("Dado cualquier revisión, cuando se aplica, entonces messageId, trazabilidad y análisis de la IA no cambian")
    void identityTraceAndAnalysisNeverChange() {
        // Dado un post
        EnrichedComment original = linkedin(false, 1);

        // Cuando se aprueba y se edita todo lo editable a la vez
        EnrichedComment revised = original.revise(new PostChange(
                null, true, List.of("spring"), Channels.X, "", "Nuevo empleo en Java", List.of("#Java"), ""));

        // Entonces el hilo de versiones (messageId) y lo que no es editable siguen intactos
        assertThat(revised.messageId()).isEqualTo(original.messageId());
        assertThat(revised.messageBatchId()).isEqualTo(original.messageBatchId());
        assertThat(revised.channelId()).isEqualTo(original.channelId());
        assertThat(revised.authorId()).isEqualTo(original.authorId());
        assertThat(revised.authorName()).isEqualTo(original.authorName());
        assertThat(revised.sentTime()).isEqualTo(original.sentTime());
        assertThat(revised.source()).isEqualTo(original.source());
        assertThat(revised.flag()).isEqualTo(original.flag());
        assertThat(revised.promptVersion()).isEqualTo(original.promptVersion());
        assertThat(revised.messageAuthor()).isEqualTo(original.messageAuthor());
        assertThat(revised.sentiment()).isEqualTo(original.sentiment());
        assertThat(revised.language()).isEqualTo(original.language());
        assertThat(revised.messageType()).isEqualTo(original.messageType());
        assertThat(revised.relevance()).isEqualTo(original.relevance());
    }

    // ---------- RF-04: no-op (nothing changes, nothing is written) ----------

    @Test
    @DisplayName("Dado un post aprobado, cuando se envía approved=true, entonces no cambia nada ni sube la versión")
    void approvingAnApprovedPostIsANoOp() {
        // Dado un post ya aprobado
        EnrichedComment original = linkedin(true, 2);

        // Cuando se vuelve a aprobar
        EnrichedComment revised = original.revise(approve(true));

        // Entonces es el mismo registro: el servicio no escribirá en OCI
        assertThat(revised).isEqualTo(original);
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post, cuando se envían los mismos valores que ya tiene, entonces es un no-op")
    void sameValuesAreANoOp() {
        // Dado un post
        EnrichedComment original = linkedin(false, 1);

        // Cuando se reenvía su propio contenido
        EnrichedComment revised = original.revise(new PostChange(
                null, false, original.topics(), original.channelPost(), null,
                original.outputContentProcessed(), original.hashtags(), original.cta()));

        // Entonces no hay versión nueva
        assertThat(revised).isEqualTo(original);
    }

    @Test
    @DisplayName("Dado un post, cuando el texto enviado solo difiere en espacios de los extremos, entonces es un no-op")
    void copyEqualAfterTrimIsANoOp() {
        // Dado un post (el constructor guarda el texto recortado)
        EnrichedComment original = linkedin(false, 1);

        // Cuando se envía el mismo texto con espacios alrededor
        EnrichedComment revised = original.revise(editCopy("  " + LINKEDIN_COPY + "  "));

        // Entonces se compara ya normalizado: nada cambió
        assertThat(revised).isEqualTo(original);
    }

    @Test
    @DisplayName("Dado un post, cuando el cambio no trae ningún campo, entonces es un no-op")
    void emptyChangeIsANoOp() {
        // Dado un post
        EnrichedComment original = linkedin(false, 1);

        // Cuando el cambio llega vacío (el controller ya lo rechaza; el dominio no depende de ello)
        EnrichedComment revised = original.revise(new PostChange(null, null, null, null, null, null, null, null));

        // Entonces nada cambia
        assertThat(revised).isEqualTo(original);
    }

    // ---------- RF-04 (enmienda 2026-10-13): revised ----------

    @Test
    @DisplayName("Dado un post sin revisar, cuando se edita el texto sin enviar revised, entonces queda revisado y sube la versión")
    void editingMarksThePostAsReviewed() {
        // Dado un post recién salido de la IA
        EnrichedComment original = linkedinReviewed(false, 1);

        // Cuando se edita solo el texto
        EnrichedComment revised = original.revise(editCopy(EDITED_COPY));

        // Entonces el backend registra que una persona lo revisó
        assertThat(revised.revised()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post sin revisar, cuando se aprueba, entonces queda revisado y aprobado")
    void approvingMarksThePostAsReviewed() {
        // Dado un post recién salido de la IA
        EnrichedComment original = linkedinReviewed(false, 1);

        // Cuando se aprueba
        EnrichedComment revised = original.revise(approve(true));

        // Entonces aprobar también es revisar
        assertThat(revised.revised()).isTrue();
        assertThat(revised.approved()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post aprobado sin revisar, cuando se desaprueba, entonces queda revisado y no apto")
    void unapprovingMarksThePostAsReviewed() {
        // Dado un post aprobado sin la marca (registro anterior a la enmienda)
        EnrichedComment original = post(Channels.LINKEDIN, null, LINKEDIN_COPY, List.of("#ONE", "#Java"), CTA,
                false, true, 1, null);

        // Cuando se desaprueba
        EnrichedComment revised = original.revise(approve(false));

        // Entonces el estado es "revisado y no apto", distinto de "por revisar"
        assertThat(revised.revised()).isTrue();
        assertThat(revised.approved()).isFalse();
    }

    @Test
    @DisplayName("Dado un post sin revisar, cuando solo se envía revised=true, entonces se guarda revisado en la versión siguiente sin tocar el contenido")
    void markingReviewedWithoutEditsIsASavedRevision() {
        // Dado un post recién salido de la IA
        EnrichedComment original = linkedinReviewed(false, 1);

        // Cuando el usuario lo revisa y no cambia nada
        EnrichedComment revised = original.revise(markReviewed());

        // Entonces es una revisión guardada: sube la versión y el post sigue igual
        assertThat(revised.revised()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(2);
        assertThat(revised.approved()).isFalse();
        assertThat(revised.outputContentProcessed()).isEqualTo(original.outputContentProcessed());
        assertThat(revised.hashtags()).isEqualTo(original.hashtags());
        assertThat(revised.cta()).isEqualTo(original.cta());
    }

    @Test
    @DisplayName("Dado un post ya revisado, cuando se envía revised=true, entonces es un no-op")
    void markingAReviewedPostIsANoOp() {
        // Dado un post ya revisado en v2
        EnrichedComment original = linkedinReviewed(true, 2);

        // Cuando se marca revisado otra vez
        EnrichedComment revised = original.revise(markReviewed());

        // Entonces es el mismo registro: el servicio no escribirá en OCI
        assertThat(revised).isSameAs(original);
    }

    @Test
    @DisplayName("Dado un post sin revisar, cuando la petición no cambia nada, entonces sigue sin revisar y en la misma versión")
    void noOpDoesNotMarkThePostAsReviewed() {
        // Dado un post recién salido de la IA (sin aprobar)
        EnrichedComment original = linkedinReviewed(false, 1);

        // Cuando se envía approved=false, que ya tenía
        EnrichedComment revised = original.revise(approve(false));

        // Entonces no hubo revisión: ni marca ni versión nueva
        assertThat(revised).isSameAs(original);
        assertThat(revised.revised()).isFalse();
        assertThat(revised.versionMessage()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dado un post revisado, cuando se vuelve a editar, entonces sigue revisado")
    void editingAReviewedPostKeepsItReviewed() {
        // Dado un post ya revisado
        EnrichedComment original = linkedinReviewed(true, 2);

        // Cuando se edita otra vez
        EnrichedComment revised = original.revise(editCopy(EDITED_COPY));

        // Entonces revised nunca vuelve a false
        assertThat(revised.revised()).isTrue();
        assertThat(revised.versionMessage()).isEqualTo(3);
    }

    @Test
    @DisplayName("Dado un registro LLM_FALLBACK, cuando se marca revisado, entonces no es revisable")
    void fallbackCannotBeMarkedReviewed() {
        // Dado una traza sin post
        EnrichedComment trace = fallback();

        // Cuando / Entonces
        assertThatThrownBy(() -> trace.revise(markReviewed()))
                .isInstanceOf(PostNotRevisableException.class);
    }

    // ---------- RF-02: absent keeps, "" and [] clear ----------

    @Test
    @DisplayName("Dado un post, cuando se editan solo los hashtags, entonces texto y CTA se conservan")
    void absentFieldsKeepTheirValue() {
        // Dado un post LinkedIn con CTA
        EnrichedComment original = linkedin(false, 1);

        // Cuando solo llegan hashtags (el resto ausente = null)
        EnrichedComment revised = original.revise(
                new PostChange(null, null, null, null, null, null, List.of("#ONE", "#Java", "#Spring"), null));

        // Entonces ausente significa "no cambia", nunca "vacía"
        assertThat(revised.hashtags()).containsExactly("#ONE", "#Java", "#Spring");
        assertThat(revised.outputContentProcessed()).isEqualTo(LINKEDIN_COPY);
        assertThat(revised.cta()).isEqualTo(CTA);
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post X con CTA, cuando se envía cta vacío, entonces el CTA queda como cadena vacía")
    void emptyStringClearsText() {
        // Dado un post X (X no exige CTA)
        EnrichedComment original = post(Channels.X, null, "Nuevo empleo", List.of("#Java"), "Síguenos",
                false, 1, null);

        // Cuando se vacía el CTA
        EnrichedComment revised = original.revise(new PostChange(null, null, null, null, null, null, null, ""));

        // Entonces se guarda "" (único valor para "sin CTA") y es un cambio
        assertThat(revised.cta()).isEmpty();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post FAQ con hashtags, cuando se envía una lista vacía, entonces se queda sin hashtags")
    void emptyListClearsList() {
        // Dado un post FAQ (admite 0..5 hashtags)
        EnrichedComment original = post(Channels.FAQ, "Despliegue en OCI",
                "¿Cómo desplegar una API en OCI?", List.of("#OCI"), "", false, 1, null);

        // Cuando se vacían los hashtags
        EnrichedComment revised = original.revise(new PostChange(null, null, null, null, null, null, List.of(), null));

        // Entonces la lista queda vacía
        assertThat(revised.hashtags()).isEmpty();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    // ---------- RF-03: channel rules on the final channelPost ----------

    @Test
    @DisplayName("Dado un post X, cuando se edita con un texto de 300 caracteres, entonces se rechaza")
    void editBreakingChannelRulesIsRejected() {
        // Dado un post X
        EnrichedComment original = post(Channels.X, null, "Nuevo empleo", List.of("#Java"), "",
                false, 1, null);

        // Cuando el texto supera los 280 de X / Entonces la revisión no se puede construir
        assertThatThrownBy(() -> original.revise(editCopy("a".repeat(300))))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Dado un post LinkedIn con 3 hashtags, cuando se cambia solo channelPost a X, entonces se rechaza")
    void changingChannelRevalidatesAgainstTheNewChannel() {
        // Dado un post LinkedIn válido con 3 hashtags
        EnrichedComment original = post(Channels.LINKEDIN, null, LINKEDIN_COPY,
                List.of("#ONE", "#Java", "#Spring"), CTA, false, 1, null);

        // Cuando pasa a X (admite 1..2) / Entonces el resto del post debe cumplir el canal nuevo
        assertThatThrownBy(() -> original.revise(
                        new PostChange(null, null, null, Channels.X, null, null, null, null)))
                .isInstanceOf(InvalidAssetException.class);
    }

    @Test
    @DisplayName("Dado un post LinkedIn, cuando pasa a FAQ con título y cta vacío, entonces queda como FAQ válido")
    void movingToFaqIsPossibleByClearingTheCta() {
        // Dado un post LinkedIn con CTA
        EnrichedComment original = linkedin(false, 1);

        // Cuando pasa a FAQ con título y vacía el CTA
        EnrichedComment revised = original.revise(new PostChange(
                null, null, null, Channels.FAQ, "Primer empleo en Java", null, null, ""));

        // Entonces cumple las reglas de FAQ
        assertThat(revised.channelPost()).isEqualTo(Channels.FAQ);
        assertThat(revised.titlePost()).isEqualTo("Primer empleo en Java");
        assertThat(revised.cta()).isEmpty();
        assertThat(revised.versionMessage()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dado un post LinkedIn, cuando pasa a FAQ sin vaciar el CTA, entonces se rechaza")
    void movingToFaqKeepingTheCtaIsRejected() {
        // Dado un post LinkedIn con CTA
        EnrichedComment original = linkedin(false, 1);

        // Cuando pasa a FAQ conservando el CTA (ausente = no cambia) / Entonces FAQ no admite CTA
        assertThatThrownBy(() -> original.revise(new PostChange(
                        null, null, null, Channels.FAQ, "Primer empleo en Java", null, null, null)))
                .isInstanceOf(InvalidAssetException.class);
    }

    // ---------- RF-03: LLM_FALLBACK has no post ----------

    @Test
    @DisplayName("Dado un registro LLM_FALLBACK, cuando se intenta aprobar, entonces no es revisable")
    void fallbackCannotBeApproved() {
        // Dado una traza de fallo del LLM (no tiene post)
        EnrichedComment original = fallback();

        // Cuando se aprueba / Entonces se rechaza como no revisable (→ 422)
        assertThatThrownBy(() -> original.revise(approve(true)))
                .isInstanceOf(PostNotRevisableException.class);
    }

    @Test
    @DisplayName("Dado un registro LLM_FALLBACK, cuando se intenta editar con un post válido, entonces no es revisable")
    void fallbackCannotBeEdited() {
        // Dado una traza de fallo del LLM
        EnrichedComment original = fallback();

        // Cuando se le intenta dar un post completo / Entonces sigue sin ser revisable
        assertThatThrownBy(() -> original.revise(new PostChange(
                        null, null, null, Channels.X, null, "Nuevo empleo", List.of("#Java"), null)))
                .isInstanceOf(PostNotRevisableException.class);
    }
}
