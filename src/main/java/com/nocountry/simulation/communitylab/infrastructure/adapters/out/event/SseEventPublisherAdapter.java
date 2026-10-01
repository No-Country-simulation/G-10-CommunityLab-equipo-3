package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import com.networknt.schema.OutputFormat;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class SseEventPublisherAdapter implements EventPublishPost {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int REPLAY_MAX = 100;
    private final Map<Source, List<SseEmitter>> emittersBySource = new ConcurrentHashMap<>();
    private final Map<Source, Deque<StoredEvent>> replayBySource = new ConcurrentHashMap<>();

    // Called by the SSE controllers (one GET per source: Discord / Telegram
    public SseEmitter subscribe(Source source, String lastEventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> subscribers = emittersBySource.computeIfAbsent(source, k -> new CopyOnWriteArrayList<>());
        subscribers.add(emitter);

        // Why all three callbacks: without them dead connections pile up in the list.
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        replayAfter(source, lastEventId, emitter);
        return emitter;
    }

    @Override
    public ResponseClient publish(ResponseClient post) {
        Source source = post.source();

        // Why messageId as event id: deterministic, doubles as Last-Event-ID on reconnect.
        StoredEvent event = new StoredEvent(post.messageId(), post);
        pushReplay(source, event);
        for (SseEmitter emitter : emittersBySource.getOrDefault(source, List.of())) {
            // Why one try per emitter: a dead client must never break delivery to the rest.
            try {
                emitter.send(SseEmitter.event()
                        .id(event.id())
                        .name("asset.created")
                        .data(event.post()));
            } catch (IOException | IllegalStateException e) {
                emittersBySource.getOrDefault(source, List.of()).remove(emitter);
            }
        }
        // Why ids only (Q3): no authorId/authorName/content/tokens in prod logs.
        log.info("Event published: messageId={} batchId={} source={}",
                post.messageId(), post.messageBatchId(), post.source());
        return post;
    }

    private void pushReplay(Source source, StoredEvent event) {
        Deque<StoredEvent> queue = replayBySource.computeIfAbsent(source, k -> new ConcurrentLinkedDeque<>());
        queue.addLast(event);
        while (queue.size() > REPLAY_MAX) {
            queue.pollFirst();
        }
    }

    private void replayAfter (Source source, String lastId, SseEmitter emitter) {
        if (lastId == null || lastId.isBlank()) {
            return;
        }
        boolean after = false;
        for (StoredEvent event : replayBySource.getOrDefault(source, new ConcurrentLinkedDeque<>())) {
            if (after) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(event.id())
                            .name("asset.created")
                            .data(event.post()));
                } catch (IOException | IllegalStateException e) {
                    emittersBySource.getOrDefault(source, List.of()).remove(emitter);
                    return;
                }
            }
            if (event.id().equals(lastId)) {
                after = true;
            }
        }
    }

    private record StoredEvent(String id, ResponseClient post) {}
}
