package com.nocountry.simulation.communitylab.application.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

/**
 * Single conversion from the buffered record to the SSE payload, shared by the live
 * {@code asset.created} and the history snapshot.
 *
 * <p>Derivado de: spec 008 RF-01 + RF-05 (el historial usa el mismo formato que el vivo) + plan 008 §1.1
 * (conversión única {@code EnrichedComment → ResponseClient}).
 */
@DisplayName("ResponseClient.from")
class ResponseClientTest {

    @Test
    @DisplayName("Dado un registro con valores distintos en cada campo, cuando se convierte, entonces cada campo del evento sale de su homónimo")
    void everyFieldComesFromTheSameNamedFieldOfTheRecord() throws Exception {
        // Dado un registro sin dos campos iguales del mismo tipo (un argumento cambiado de sitio no pasaría)
        EnrichedComment comment = distinctRecord();

        // Cuando se convierte
        ResponseClient response = ResponseClient.from(comment);

        // Entonces cada componente coincide con el del registro que se llama igual
        for (RecordComponent component : ResponseClient.class.getRecordComponents()) {
            Object expected = EnrichedComment.class.getMethod(component.getName()).invoke(comment);
            assertThat(component.getAccessor().invoke(response))
                    .as(component.getName())
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Dado el registro, cuando se convierte, entonces no viajan los campos internos (authorId, channelId, promptVersion)")
    void internalFieldsNeverReachTheEvent() {
        // Cuando se mira el contrato del evento / Entonces solo lleva lo que el dashboard muestra (Q3)
        assertThat(ResponseClient.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("authorId", "channelId", "promptVersion");
    }

    private static EnrichedComment distinctRecord() {
        return new EnrichedComment(
                "batch-7", "msg-42", "channel-9", "author-id-3", "Ana Dev",
                "Consegui mi primer empleo como dev Java, gracias comunidad",
                Sentiment.POSITIVO, Language.ES, MessageType.LOGRO,
                List.of("empleo", "java"), 87, null, "prompt-v2",
                Instant.parse("2026-10-06T10:00:00Z"), Source.TELEGRAM,
                Channels.LINKEDIN, "Titulo del post",
                "De la comunidad al primer empleo: hoy celebramos a una dev Java que consiguio su primer trabajo gracias al apoyo de todos.",
                List.of("#ONE", "#Java"), "Comparte tu historia",
                true, ApprovalStatus.IN_REVIEW, 2, null, null);
    }
}
