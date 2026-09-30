package com.nocountry.simulation.communitylab.infrastructure.adapters.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.nocountry.simulation.communitylab.application.command.IngestTelegramCommand;
import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseTelegram;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.adapters.in.mapper.TelegramMessageMapper;
import com.nocountry.simulation.communitylab.infrastructure.config.telegram.TelegramBotProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Tests for the Telegram inbound adapter.
 * Mirror of {@code DiscordMessageListenerTest}: same package as the source
 * for fast lookup.
 *
 * <p>Derivado de: spec 005 RF-01,RF-02 (filtros bot/no-message/no-text) + plan §1.
 * No Spring context: IngestUseCaseTelegram mock (puerto), mapper real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramMessageListener")
class TelegramMessageListenerTest {

    @Mock
    private IngestUseCaseTelegram useCase;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private User from;

    @Mock
    private Chat chat;

    private TelegramMessageListener listener;

    @BeforeEach
    void setUpListener() {
        // Why: listener is a thin adapter, real mapper + mock use case isolates filtering.
        listener = new TelegramMessageListener(
                new TelegramBotProperties("test-token"),
                new TelegramMessageMapper(),
                useCase);
    }

    @Nested
    @DisplayName("Filtering and delegation")
    class FilteringAndDelegation {

        @Test
        @DisplayName("Dado update sin mensaje, cuando llega, entonces se descarta")
        void discardsUpdateWithoutMessage() {
            // Dado un update sin mensaje
            when(update.getMessage()).thenReturn(null);
            when(update.hasMessage()).thenReturn(false);

            // Cuando se consume
            listener.consume(update);

            // Entonces no delega al caso de uso
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Dado autor bot, cuando llega mensaje, entonces se descarta")
        void discardsBotMessage() {
            // Dado un mensaje de un bot
            when(update.hasMessage()).thenReturn(true);
            when(update.getMessage()).thenReturn(message);
            when(message.getFrom()).thenReturn(from);
            when(from.getIsBot()).thenReturn(Boolean.TRUE);

            // Cuando se consume
            listener.consume(update);

            // Entonces no delega al caso de uso
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Dado mensaje sin texto, cuando llega, entonces se descarta")
        void discardsNonTextMessage() {
            // Dado un mensaje sin texto (sticker/foto)
            when(update.hasMessage()).thenReturn(true);
            when(update.getMessage()).thenReturn(message);
            when(message.getFrom()).thenReturn(from);
            when(from.getIsBot()).thenReturn(Boolean.FALSE);
            when(message.getText()).thenReturn(null);
            when(message.getMessageId()).thenReturn(1001);

            // Cuando se consume
            listener.consume(update);

            // Entonces no delega al caso de uso
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Dado texto en blanco, cuando llega, entonces se descarta")
        void discardsBlankMessage() {
            // Dado un mensaje solo-blancos
            when(update.hasMessage()).thenReturn(true);
            when(update.getMessage()).thenReturn(message);
            when(message.getFrom()).thenReturn(from);
            when(from.getIsBot()).thenReturn(Boolean.FALSE);
            when(message.getText()).thenReturn("   ");
            when(message.getMessageId()).thenReturn(1001);

            // Cuando se consume
            listener.consume(update);

            // Entonces no delega al caso de uso (el dominio tambien lo rechazaria)
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Dado mensaje valido, cuando llega, entonces delega intacto al caso de uso")
        void delegatesValidMessage() {
            // Dado un mensaje valido de humano
            givenFullMessage(1001, 555L, 777L, "cos_dev", "Consegui mi primer empleo Java!", 1727000000);
            ChannelMessage accepted = new ChannelMessage(
                    "batch-1", "1001", "555", "777", "cos_dev",
                    "Consegui mi primer empleo Java!",
                    Instant.ofEpochSecond(1727000000), false, Source.TELEGRAM);
            when(useCase.ingest(any(IngestTelegramCommand.class))).thenReturn(Optional.of(accepted));

            // Cuando se consume
            listener.consume(update);

            // Entonces delega con contenido intacto (el trim/trunca vive en dominio)
            IngestTelegramCommand delegated = captureDelegated();
            assertThat(delegated.messageId()).isEqualTo(1001);
            assertThat(delegated.channelId()).isEqualTo(555L);
            assertThat(delegated.authorId()).isEqualTo(777L);
            assertThat(delegated.content()).isEqualTo("Consegui mi primer empleo Java!");
        }

        @Test
        @DisplayName("Dado downstream invalido, cuando delega, entonces no lanza y marca rejected")
        void downstreamRejectionDoesNotThrow() {
            // Dado un mensaje que el caso de uso rechaza (ej. vacio tras trim en dominio)
            givenFullMessage(1002, 555L, 777L, "cos_dev", "x", 1727000000);
            when(useCase.ingest(any(IngestTelegramCommand.class))).thenReturn(Optional.empty());

            // Cuando se consume entonces no propaga excepcion
            listener.consume(update);

            // Entonces si llamo al caso de uso una vez
            verify(useCase).ingest(any(IngestTelegramCommand.class));
        }

        private IngestTelegramCommand captureDelegated() {
            ArgumentCaptor<IngestTelegramCommand> captor = ArgumentCaptor.forClass(IngestTelegramCommand.class);
            verify(useCase).ingest(captor.capture());
            return captor.getValue();
        }
    }

    private void givenFullMessage(Integer messageId, Long chatId, Long authorId,
            String authorName, String content, Integer epochSeconds) {
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);
        when(from.getIsBot()).thenReturn(Boolean.FALSE);
        when(message.getText()).thenReturn(content);
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getChat()).thenReturn(chat);
        when(chat.getId()).thenReturn(chatId);
        // getFrom stubbed twice (Mockito permite re-stub): id + username
        when(from.getId()).thenReturn(authorId);
        when(from.getUserName()).thenReturn(authorName);
        when(message.getDate()).thenReturn(epochSeconds);
    }
}
