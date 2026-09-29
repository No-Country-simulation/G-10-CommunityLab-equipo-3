package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class SseEventPublisherAdapter implements EventPublishPost {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int REPLAY_MAX = 100;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<StoredEvent> replay = new ConcurrentLinkedDeque<>();

    // Called by the SSE controller (single GET /api/v1/discord/messages).
    public SseEmitter subscribe(String lastEventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        // Why all three callbacks: without them dead connections pile up in the list.
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        replayAfter(lastEventId, emitter);
        return emitter;
    }

    @Override
    public ResponseClient publish(ResponseClient post) {
        // Why messageId as event id: deterministic, doubles as Last-Event-ID on reconnect.
        StoredEvent event = new StoredEvent(post.messageId(), post);
        pushReplay(event);
        for (SseEmitter emitter : emitters) {
            // Why one try per emitter: a dead client must never break delivery to the rest.
            try {
                emitter.send(SseEmitter.event()
                        .id(event.id())
                        .name("asset.created")
                        .data(event.post()));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
        // Why ids only (Q3): no authorId/authorName/content/tokens in prod logs.
        log.info("Event published: messageId={} batchId={} source={}",
                post.messageId(), post.messageBatchId(), post.source());
        return post;
    }

    private void pushReplay(StoredEvent event) {
        replay.addLast(event);
        while (replay.size() > REPLAY_MAX) {
            replay.pollFirst();
        }
    }

    private void replayAfter(String lastId, SseEmitter emitter) {
        if (lastId == null || lastId.isBlank()) {
            return;
        }
        boolean after = false;
        for (StoredEvent event : replay) {
            if (after) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(event.id())
                            .name("asset.created")
                            .data(event.post()));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
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
