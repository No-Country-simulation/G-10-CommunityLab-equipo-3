package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.discordMessage;

import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.domain.enums.Source;
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

@Tag(name = "Events", description = "Single public read endpoint: live SSE stream of processed posts and stored packages.")
@RestController
@RequiredArgsConstructor
@Slf4j
public class GetMessagesProcessedDiscord {

    private final SseEventPublisherAdapter publisher;

    @Operation(
            summary = "Subscribe to processed posts",
            description = """
                    Opens a long-lived SSE stream with three event types, all only for Discord:
                    - `asset.created` (id = messageId): one per processed Discord message (post + analysis metadata).
                    - `package.completed` (id = batchId): one per stored package. `payload.parUrlBase` is a \
                    read-only pre-authenticated URL to `GET` the package JSON directly from OCI until \
                    `payload.expiresAt` (ISO-8601); treat it as a secret and never log it.
                    - `message.revised` (id = messageId:v{versionMessage}): a post was approved or edited and \
                    saved as a new version. No content: reload the package through `parUrlBase`.
                    Reconnect with the last received event id to replay missed events of every type. \
                    Swagger "Try it out" does not render streams; consume from the frontend with:
                    `const es = new EventSource('/api/v1/discord/messages'); \
                    es.addEventListener('asset.created', e => render(JSON.parse(e.data))); \
                    es.addEventListener('package.completed', e => loadPackage(JSON.parse(e.data).payload)); \
                    es.addEventListener('message.revised', e => markRevised(JSON.parse(e.data).payload));`""")
    @ApiResponse(
            responseCode = "200",
            description = "Stream opened; events flow as `asset.created`, `package.completed` and `message.revised` until timeout (~30min), then reconnect.",
            content = @Content(
                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = ResponseClient.class),
                    examples = {
                            @ExampleObject(
                                    name = "asset.created",
                                    summary = "Processed LinkedIn post with metadata",
                                    value = """
                                            {
                                              "authorName": "author-name",
                                              "messageAuthor": "Ana_dev",
                                              "messageId": "msg-1",
                                              "messageBatchId": "b3e1a2c4-0000-4000-8000-000000000001",
                                              "sentiment": "POSITIVO",
                                              "language": "ES",
                                              "messageType": "LOGRO",
                                              "topics": ["empleo", "java", "logro"],
                                              "relevance": 85,
                                              "flag": null,
                                              "sentTime": "2026-09-28T10:00:00Z",
                                              "source": "DISCORD",
                                              "channelPost": "LINKEDIN",
                                              "titlePost": null,
                                              "outputContentProcessed": "De la comunidad al primer empleo como dev Java. Gracias por el apoyo en el camino!",
                                              "hashtags": ["#ONE", "#EmpleoTech", "#Java"],
                                              "cta": "Comparte tu historia en #logros",
                                              "revised": false,
                                              "approved": false,
                                              "versionMessage": 1
                                            }"""),
                            @ExampleObject(
                                    name = "package.completed",
                                    summary = "Discord package stored in OCI, readable through its PAR",
                                    value = """
                                            {
                                              "type": "package.completed",
                                              "batchId": "b3e1a2c4-0000-4000-8000-000000000001",
                                              "payload": {
                                                "assetsCount": 12,
                                                "parUrlBase": "https://objectstorage.sa-saopaulo-1.oraclecloud.com/p/<par-token>/n/<namespace>/b/<bucket>/o/paquetes/discord/paquete-b3e1a2c4-0000-4000-8000-000000000001.json",
                                                "expiresAt": "2026-10-11T10:00:00Z"
                                              }
                                            }"""),
                            @ExampleObject(
                                    name = "message.revised",
                                    summary = "A Discord post was approved or edited and saved as a new version",
                                    value = """
                                            {
                                              "type": "message.revised",
                                              "batchId": "b3e1a2c4-0000-4000-8000-000000000001",
                                              "payload": {
                                                "messageId": "msg-1",
                                                "versionMessage": 2,
                                                "approved": true,
                                                "revised": true
                                              }
                                            }""")
                    }))
    @GetMapping(value = "/api/v1/discord/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getMessagesProcessedDiscord(
            @Parameter(description = "Last received event id for replay; omit on first connect.", example = "msg-1")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent) {
        log.debug("sse subscribe: lastEventId={}", lastEvent);
        return publisher.subscribe(Source.DISCORD,lastEvent);
    }
}
