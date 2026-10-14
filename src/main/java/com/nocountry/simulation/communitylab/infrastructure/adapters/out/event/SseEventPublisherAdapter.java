package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.application.port.out.PackagePublisher;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SseEventPublisherAdapter implements EventPublishPost, PackagePublisher {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int REPLAY_MAX = 100;
    private static final long HEARTBEAT_SECONDS = 30;

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

        // Why: Cloudflare cuts a stream silent for 100 s (524); a comment sends a byte without firing onmessage or moving Last-Event-ID.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
            return emitter;
        }
        replayAfter(source, lastEventId, emitter);
        return emitter;
    }

    // Why: 30 s stays well under Cloudflare's 100 s idle cut, even if one beat is late or lost.
    @Scheduled(fixedRate = HEARTBEAT_SECONDS, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        for (List<SseEmitter> subscribers : emittersBySource.values()) {
            for(SseEmitter emitter : subscribers) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                   subscribers.remove(emitter);
                }
            }
        }
    }

    @Override
    public ResponseClient publish(ResponseClient post) {
        Source source = post.source();

        // Why messageId as event id: deterministic, doubles as Last-Event-ID on reconnect.
        StoredEvent event = new StoredEvent(post.messageId(), "asset.created", post);
        pushReplay(source, event);
        sendToAll(source, event);

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

    private void sendToAll(Source source, StoredEvent event) {
        for (SseEmitter emitter : emittersBySource.getOrDefault(source, List.of())) {
            // Why one try per emitter: a dead client must never break delivery to the rest.
            try {
                emitter.send(SseEmitter.event()
                        .id(event.id())
                        .name(event.name())
                        .data(event.data()));
            } catch (IOException | IllegalStateException e) {
                emittersBySource.getOrDefault(source, List.of()).remove(emitter);
            }
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
                            .name(event.name())
                            .data(event.data()));
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

    @Override
    public void publishPackage(PackageCompleted packageCompleted) {

        PackageCompletedEvent packageCompletedEvent = new PackageCompletedEvent(
                "package.completed",
                packageCompleted.batchId(),
                new Payload(packageCompleted.assetsCount(), packageCompleted.parUrlBase(), packageCompleted.expiresAt()));

        StoredEvent event = new StoredEvent(
                packageCompleted.batchId(),
                "package.completed",
                packageCompletedEvent
        );

        pushReplay(packageCompleted.source(), event);

        sendToAll(packageCompleted.source(), event);

        // Why ids only: parUrlBase is a bearer secret.
        log.info("Package completed published: batchId={} source={}",
                packageCompleted.batchId(), packageCompleted.source());
    }

    @Override
    public void publishRevision(MessageRevised messageRevised) {

        MessageRevisedEvent messageRevisedEvent = new MessageRevisedEvent(
                "message.revised",
                messageRevised.batchId(),
                new RevisionPayload(messageRevised.messageId(), messageRevised.versionMessage(), messageRevised.approved(), messageRevised.revised()));

        StoredEvent event = new StoredEvent(
                messageRevised.messageId() + ":v" + messageRevised.versionMessage(),
                "message.revised",
                messageRevisedEvent
        );

        pushReplay(messageRevised.source(), event);

        sendToAll(messageRevised.source(), event);

        log.info("Message revised published: source={} batchId={} messageId={} versionMessage={}",
                messageRevised.source(), messageRevised.batchId(), messageRevised.messageId(),
                messageRevised.versionMessage());
    }

    private record StoredEvent(String id, String name, Object data) {}

    private record MessageRevisedEvent(String type, String batchId, RevisionPayload payload) {}

    private record RevisionPayload(String messageId, int versionMessage, boolean approved, boolean revised) {}

    private record PackageCompletedEvent(String type, String batchId, Payload payload){

    }

    private record Payload(int assetsCount, String parUrlBase, Instant expiresAt){

    }
}
