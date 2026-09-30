package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.telegramMessage;

import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.infrastructure.adapters.out.event.SseEventPublisherAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Events", description = "Single public read endpoint: live SSE stream of processed posts.")
@RestController
@RequiredArgsConstructor
@Slf4j
public class GetMessageProcessedTelegram {
    private final SseEventPublisherAdapter publisher;

    // Why the payload schema points at ResponseClient: SseEmitter itself carries
    // no type information for springdoc, so the documented contract is the event
    // data (post + metadata). Domain stays free of swagger annotations (R3).
    @Operation(
            summary = "Subscribe to processed Telegram posts",
            description = """
                    Opens a long-lived SSE stream emitting one `asset.created` event per \
                    processed Telegram message (post + analysis metadata). \
                    Reconnect with the last received event id to replay missed events. \
                    Swagger "Try it out" does not render streams; consume from the frontend with:
                    `const es = new EventSource('/api/v1/telegram/messages'); \
                    es.addEventListener('asset.created', e => render(JSON.parse(e.data)));`""")
    @ApiResponse(
            responseCode = "200",
            description = "Stream opened; events flow as `asset.created` until timeout (~30min), then reconnect.",
            content = @Content(
                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = ResponseClient.class),
                    examples = @ExampleObject(
                            name = "asset.created",
                            summary = "Processed Telegram post with metadata",
                            value = """
                                    {
                                      "authorName": "author-name",
                                      "messageAuthor": "Ana_dev",
                                      "messageId": "tg-1",
                                      "messageBatchId": "b3e1a2c4-0000-4000-8000-000000000001",
                                      "sentiment": "POSITIVO",
                                      "language": "ES",
                                      "messageType": "LOGRO",
                                      "topics": ["empleo", "java", "logro"],
                                      "relevance": 85,
                                      "flag": null,
                                      "sentTime": "2026-09-28T10:00:00Z",
                                      "source": "TELEGRAM",
                                      "channelPost": "LINKEDIN",
                                      "titlePost": null,
                                      "outputContentProcessed": "De la comunidad al primer empleo como dev Java. Gracias por el apoyo en el camino!",
                                      "hashtags": ["#ONE", "#EmpleoTech", "#Java"],
                                      "cta": "Comparte tu historia en #logros"
                                    }""")))
    @GetMapping(value = "/api/v1/telegram/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getMessagesProcessedTelegram(
            @Parameter(description = "Last received event id for replay; omit on first connect.", example = "tg-1")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent
    ) {
        log.debug("sse subscribe telegram: lastEventId={}", lastEvent);
        return publisher.subscribe(lastEvent);
    }
}
