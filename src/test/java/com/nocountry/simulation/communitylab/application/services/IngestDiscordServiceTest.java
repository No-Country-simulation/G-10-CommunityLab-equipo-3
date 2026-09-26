package com.nocountry.simulation.communitylab.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nocountry.simulation.communitylab.application.command.IngestDiscordCommand;
import com.nocountry.simulation.communitylab.domain.enums.Source;

/**
 * Tests for the Discord use case.
 * Mirror of {@code IngestDiscordService}: same package as the source.
 */
@DisplayName("IngestDiscordService")
class IngestDiscordServiceTest {

    private final IngestDiscordService useCase = new IngestDiscordService();

    @Test
    @DisplayName("Given valid command, when ingested, then it returns the normalized DTO")
    void returnsNormalizedDto() {
        // Given a valid command with padded content
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-1",
                "listen-123",
                "author-1",
                "author-name",
                "  sample content  ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // When ingested
        var result = useCase.ingest(command);

        // Then normalized DTO (trimmed, trace preserved)
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("sample content");
        assertThat(result.get().truncated()).isFalse();
        assertThat(result.get().channelId()).isEqualTo("listen-123");
        assertThat(result.get().source()).isEqualTo(Source.DISCORD);
    }

    @Test
    @DisplayName("Given blank content, when ingested, then it returns empty")
    void discardsBlankMessage() {
        // Given a blank-only command
        IngestDiscordCommand command = new IngestDiscordCommand(
                "msg-2",
                "listen-123",
                "author-1",
                "author-name",
                "   ",
                Instant.parse("2026-09-24T10:00:00Z"));

        // When ingested then discarded silently
        assertThat(useCase.ingest(command)).isEmpty();
    }
}
