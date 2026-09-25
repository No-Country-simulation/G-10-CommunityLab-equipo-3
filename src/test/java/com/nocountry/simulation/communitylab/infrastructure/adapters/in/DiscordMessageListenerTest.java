package com.nocountry.simulation.communitylab.infrastructure.adapters.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.application.port.in.IngestUseCaseDiscord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/**
 * Tests for the Discord inbound adapter.
 * Mirror of {@code DiscordMessageListener}: same package as the source
 * for fast lookup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordMessageListener")
class DiscordMessageListenerTest {

    private static final String LISTENED_CHANNEL = "listen-123";
    private static final String OTHER_CHANNEL = "other-999";
    private static final int MAX_LENGTH = 2000;

    @Mock
    private IngestUseCaseDiscord useCase;

    @Mock
    private MessageReceivedEvent event;

    @Mock
    private User author;

    @Mock
    private MessageChannelUnion channel;

    @Mock
    private Message message;

    private DiscordMessageListener listener;

    @BeforeEach
    void setUpListener() {
        listener = new DiscordMessageListener(useCase, LISTENED_CHANNEL);
    }

    @Nested
    @DisplayName("Filtering and delegation")
    class FilteringAndDelegation {

        @Test
        @DisplayName("Given bot author, when message arrives, then it is discarded")
        void discardsBotMessage() {
            // Given a message from a bot
            givenBaseEvent("hello", true, LISTENED_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then it is not delegated to the use case
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Given other channel, when message arrives, then it is discarded")
        void discardsOtherChannelMessage() {
            // Given a message outside the listened channel
            givenBaseEvent("hello", false, OTHER_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then it is not delegated to the use case
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Given empty content, when message arrives, then it is discarded")
        void discardsEmptyMessage() {
            // Given a blank-only message
            givenBaseEvent("   ", false, LISTENED_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then it is not delegated to the use case
            verifyNoInteractions(useCase);
        }

        @Test
        @DisplayName("Given short valid message, when it arrives, then it is delegated intact")
        void delegatesShortValidMessage() {
            // Given a short 29-char message (Index 1999 regression)
            String content = "a".repeat(29);
            givenEvent(content, false, LISTENED_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then it is delegated with intact content
            IngestDiscordCommand delegated = captureDelegatedMessage();
            assertThat(delegated.content()).isEqualTo(content);
            assertThat(delegated.channelId()).isEqualTo(LISTENED_CHANNEL);
        }

        @Test
        @DisplayName("Given long message, when it arrives, then it is delegated raw for domain normalization")
        void delegatesLongMessageRaw() {
            // Given a 3500-char message
            String content = "a".repeat(3500);
            givenEvent(content, false, LISTENED_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then the raw content is delegated untouched;
            // truncation lives only in MessageContent (domain, covered by CommentTest)
            IngestDiscordCommand delegated = captureDelegatedMessage();
            assertThat(delegated.content()).isEqualTo(content);
        }

        @Test
        @DisplayName("Given exact 2000-char message, when it arrives, then it is delegated intact")
        void delegatesExactLengthMessage() {
            // Given a message right at the limit
            String content = "a".repeat(MAX_LENGTH);
            givenEvent(content, false, LISTENED_CHANNEL);

            // When it is received
            listener.onMessageReceived(event);

            // Then it is delegated without trimming
            IngestDiscordCommand delegated = captureDelegatedMessage();
            assertThat(delegated.content()).isEqualTo(content);
        }

        private IngestDiscordCommand captureDelegatedMessage() {
            ArgumentCaptor<IngestDiscordCommand> captor = ArgumentCaptor.forClass(IngestDiscordCommand.class);
            verify(useCase).ingest(captor.capture());
            return captor.getValue();
        }
    }

    private void givenEvent(String content, boolean isBot, String channelId) {
        givenBaseEvent(content, isBot, channelId);
        givenDelegationDetails();
    }

    private void givenBaseEvent(String content, boolean isBot, String channelId) {
        when(event.getAuthor()).thenReturn(author);
        when(event.getChannel()).thenReturn(channel);
        when(event.getMessage()).thenReturn(message);

        when(author.isBot()).thenReturn(isBot);
        when(channel.getId()).thenReturn(channelId);

        when(message.getContentRaw()).thenReturn(content);
    }

    private void givenDelegationDetails() {
        when(message.getId()).thenReturn("msg-1");
        when(author.getId()).thenReturn("author-1");
        when(author.getName()).thenReturn("author-name");
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-09-24T10:00:00Z"));
    }
}
