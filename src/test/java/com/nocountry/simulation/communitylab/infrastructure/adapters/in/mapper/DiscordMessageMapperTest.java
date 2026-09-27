package com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/**
 * Unit tests for {@code DiscordMessageMapper}.
 * Covers spec 001 RF-01a/RF-04: pure JDA event to command translation,
 * no trimming, no truncation, event timestamp preserved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordMessageMapper")
class DiscordMessageMapperTest {

    @Mock
    private MessageReceivedEvent event;

    @Mock
    private User author;

    @Mock
    private MessageChannelUnion channel;

    @Mock
    private Message message;

    private DiscordMessageMapper mapper;

    @BeforeEach
    void setUp() {
        // Why: mapper is stateless, no Spring context needed for unit tests.
        mapper = new DiscordMessageMapper();
    }

    @Test
    @DisplayName("Dado evento valido, cuando mapea, entonces traslada los 6 campos 1 a 1")
    void mapsAllFieldsOneToOne() {
        // Dado un evento JDA completo
        OffsetDateTime timeCreated = OffsetDateTime.parse("2026-09-24T10:00:00Z");
        givenEvent("msg-1", "listen-123", "author-1", "Cos_dev", "Consegui mi primer empleo Java!", timeCreated);

        // Cuando se mapea a comando
        IngestDiscordCommand result = mapper.fromMessageReceivedEvent(event);

        // Entonces cada campo refleja el evento sin transformacion
        assertThat(result.messageId()).isEqualTo("msg-1");
        assertThat(result.channelId()).isEqualTo("listen-123");
        assertThat(result.authorId()).isEqualTo("author-1");
        assertThat(result.authorName()).isEqualTo("Cos_dev");
        assertThat(result.content()).isEqualTo("Consegui mi primer empleo Java!");
        assertThat(result.sentTime()).isEqualTo(timeCreated.toInstant());
    }

    @Test
    @DisplayName("Dado contenido con espacios, cuando mapea, entonces lo preserva crudo sin trim")
    void preservesRawContentWithoutTrim() {
        // Dado contenido con espacios laterales
        String raw = "  hola  ";
        givenEvent("msg-2", "listen-123", "author-1", "Cos_dev", raw,
                OffsetDateTime.parse("2026-09-24T10:00:00Z"));

        // Cuando se mapea
        IngestDiscordCommand result = mapper.fromMessageReceivedEvent(event);

        // Entonces el trim vive solo en dominio (MessageContent), aqui intacto
        assertThat(result.content()).isEqualTo(raw);
    }

    @Test
    @DisplayName("Dado texto >2000 chars, cuando mapea, entonces lo delega intacto sin truncar")
    void preservesLongContentWithoutTruncation() {
        // Dado un texto largo de 3500 chars
        String raw = "a".repeat(3500);
        givenEvent("msg-3", "listen-123", "author-1", "Cos_dev", raw,
                OffsetDateTime.parse("2026-09-24T10:00:00Z"));

        // Cuando se mapea
        IngestDiscordCommand result = mapper.fromMessageReceivedEvent(event);

        // Entonces no trunca ni marca flag; dominio decide via MessageContent
        assertThat(result.content()).isEqualTo(raw);
        assertThat(result.content().length()).isEqualTo(3500);
    }

    @Test
    @DisplayName("Dado timestamp del evento, cuando mapea, entonces usa el del evento y no Instant.now()")
    void preservesEventTimestamp() {
        // Dado un timestamp fijo del evento
        OffsetDateTime timeCreated = OffsetDateTime.parse("2026-09-18T15:00:00Z");
        givenEvent("msg-4", "listen-123", "author-1", "Cos_dev", "hola",
                timeCreated);

        // Cuando se mapea
        IngestDiscordCommand result = mapper.fromMessageReceivedEvent(event);

        // Entonces respeta RF-04: timestamp del evento normalizado, no hora de proceso
        assertThat(result.sentTime()).isEqualTo(timeCreated.toInstant());
    }

    private void givenEvent(String messageId, String channelId, String authorId,
            String authorName, String content, OffsetDateTime timeCreated) {
        when(event.getMessageId()).thenReturn(messageId);
        when(event.getChannel()).thenReturn(channel);
        when(event.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(message);
        when(channel.getId()).thenReturn(channelId);
        when(author.getId()).thenReturn(authorId);
        when(author.getName()).thenReturn(authorName);
        when(message.getContentRaw()).thenReturn(content);
        when(message.getTimeCreated()).thenReturn(timeCreated);
    }
}
