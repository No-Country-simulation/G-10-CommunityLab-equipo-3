package com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Unit tests for {@code TelegramMessageMapper}.
 * Covers spec 005 RF-01: pure Telegram Message to command translation,
 * no trimming, no truncation, event timestamp preserved.
 * No Spring context: Mockito only at SDK boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramMessageMapper")
class TelegramMessageMapperTest {

    @Mock
    private Message message;

    @Mock
    private Chat chat;

    @Mock
    private User from;

    private TelegramMessageMapper mapper;

    @BeforeEach
    void setUp() {
        // Why: mapper is stateless, no Spring context needed for unit tests.
        mapper = new TelegramMessageMapper();
    }

    @Test
    @DisplayName("Dado mensaje valido, cuando mapea, entonces traslada los 6 campos 1 a 1")
    void mapsAllFieldsOneToOne() {
        // Dado un mensaje Telegram completo
        int epochSeconds = 1727000000;
        givenMessage(1001, 555L, 777L, "cos_dev", "Consegui mi primer empleo Java!", epochSeconds);

        // Cuando se mapea a comando
        IngestTelegramCommand result = mapper.toIngestTelegramCommand(message);

        // Entonces cada campo refleja el mensaje sin transformacion
        assertThat(result.messageId()).isEqualTo(1001);
        assertThat(result.channelId()).isEqualTo(555L);
        assertThat(result.authorId()).isEqualTo(777L);
        assertThat(result.authorName()).isEqualTo("cos_dev");
        assertThat(result.content()).isEqualTo("Consegui mi primer empleo Java!");
        assertThat(result.sentTime()).isEqualTo(Instant.ofEpochSecond(epochSeconds));
    }

    @Test
    @DisplayName("Dado contenido con espacios, cuando mapea, entonces lo preserva crudo sin trim")
    void preservesRawContentWithoutTrim() {
        // Dado contenido con espacios laterales
        String raw = "  hola  ";
        givenMessage(1002, 555L, 777L, "cos_dev", raw, 1727000000);

        // Cuando se mapea
        IngestTelegramCommand result = mapper.toIngestTelegramCommand(message);

        // Entonces el trim vive solo en dominio (MessageContent), aqui intacto
        assertThat(result.content()).isEqualTo(raw);
    }

    @Test
    @DisplayName("Dado texto >2000 chars, cuando mapea, entonces lo delega intacto sin truncar")
    void preservesLongContentWithoutTruncation() {
        // Dado un texto largo de 3500 chars
        String raw = "a".repeat(3500);
        givenMessage(1003, 555L, 777L, "cos_dev", raw, 1727000000);

        // Cuando se mapea
        IngestTelegramCommand result = mapper.toIngestTelegramCommand(message);

        // Entonces no trunca ni marca flag; dominio decide via MessageContent
        assertThat(result.content()).isEqualTo(raw);
        assertThat(result.content().length()).isEqualTo(3500);
    }

    @Test
    @DisplayName("Dado timestamp del mensaje, cuando mapea, entonces usa el del evento y no Instant.now()")
    void preservesEventTimestamp() {
        // Dado un timestamp fijo del mensaje
        int epochSeconds = 1726990000;
        givenMessage(1004, 555L, 777L, "cos_dev", "hola", epochSeconds);

        // Cuando se mapea
        IngestTelegramCommand result = mapper.toIngestTelegramCommand(message);

        // Entonces respeta RF-01: timestamp del Update normalizado, no hora de proceso
        assertThat(result.sentTime()).isEqualTo(Instant.ofEpochSecond(epochSeconds));
    }

    private void givenMessage(Integer messageId, Long chatId, Long authorId,
            String authorName, String content, Integer epochSeconds) {
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getChat()).thenReturn(chat);
        when(message.getFrom()).thenReturn(from);
        when(message.getText()).thenReturn(content);
        when(message.getDate()).thenReturn(epochSeconds);
        when(chat.getId()).thenReturn(chatId);
        when(from.getId()).thenReturn(authorId);
        when(from.getUserName()).thenReturn(authorName);
    }
}
