package com.nocountry.simulation.communitylab.infrastructure.adapters.out.event;

import com.nocountry.simulation.communitylab.application.dtos.History;
import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.dtos.ResponseClient;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.application.port.out.PackagePublisher;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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
    private final ReentrantLock heartbeatLock = new ReentrantLock();

    // Called by the SSE controllers (one GET per source); history is loaded only when the replay cannot continue.
    public SseEmitter subscribe(Source source, String lastEventId, Supplier<History> history) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> subscribers = emittersBySource.computeIfAbsent(source, k -> new CopyOnWriteArrayList<>());
        subscribers.add(emitter);

        // Why all three callbacks: without them dead connections pile up in the list.
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        try {
            // Why: Cloudflare cuts a stream silent for 100 s (524); a comment sends a byte without firing onmessage or moving Last-Event-ID.
            emitter.send(SseEmitter.event().comment("connected"));

            // Why: a known Last-Event-ID resumes from memory; absent or unknown (redeploy, out of the window) gets the full history
            InitialBlock block = canReplay(source, lastEventId)
                    ? replayAfter(source, lastEventId, emitter)
                    : sendHistory(history.get(), emitter);

            // Why no id: snapshot.completed must not move the browser's Last-Event-ID
            emitter.send(SseEmitter.event()
                    .name("snapshot.completed")
                    .data(new SnapshotCompleted("snapshot.completed", block.packages(), block.pending())));

            log.info("sse subscribed: source={} packages={} pending={}", source, block.packages(), block.pending());
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    // Why: 30 s stays well under Cloudflare's 100 s idle cut, even if one beat is late or lost.
    @Scheduled(fixedRate = HEARTBEAT_SECONDS, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        // Why skip instead of wait (spec 010): on virtual threads every fixedRate run starts on a new thread without
        // waiting for the last one, so a client that stopped reading would pile up heartbeats blocked on its socket.
        if (!heartbeatLock.tryLock()) {
            log.debug("sse heartbeat skipped: previous beat still running");
            return;
        }
        try {
            for (List<SseEmitter> subscribers : emittersBySource.values()) {
                for(SseEmitter emitter : subscribers) {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException | IllegalStateException e) {
                       subscribers.remove(emitter);
                    }
                }
            }
        } finally {
            heartbeatLock.unlock();
        }
    }

    @Override
    public ResponseClient publish(ResponseClient post) {
        Source source = post.source();

        // Why messageId as event id: deterministic, doubles as Last-Event-ID on reconnect.
        StoredEvent event = assetEvent(post);
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
                send(emitter, event);
            } catch (IOException | IllegalStateException e) {
                emittersBySource.getOrDefault(source, List.of()).remove(emitter);
            }
        }
    }

    private boolean canReplay(Source source, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return false;
        }
        return replayBySource.getOrDefault(source, new ConcurrentLinkedDeque<>()).stream()
                .anyMatch(event -> event.id().equals(lastEventId));
    }

    private InitialBlock replayAfter(Source source, String lastId, SseEmitter emitter) throws IOException {
        int packages = 0;
        int pending = 0;
        boolean after = false;
        for (StoredEvent event : replayBySource.getOrDefault(source, new ConcurrentLinkedDeque<>())) {
            if (after) {
                send(emitter, event);
                if ("package.completed".equals(event.name())) {
                    packages++;
                } else if ("asset.created".equals(event.name())) {
                    pending++;
                }
            }
            if (event.id().equals(lastId)) {
                after = true;
            }
        }
        return new InitialBlock(packages, pending);
    }

    private InitialBlock sendHistory(History history, SseEmitter emitter) throws IOException {
        for (PackageCompleted completed : history.packages()) {
            send(emitter, packageEvent(completed));
        }
        for (ResponseClient post : history.pending()) {
            send(emitter, assetEvent(post));
        }
        return new InitialBlock(history.packages().size(), history.pending().size());
    }

    private static void send(SseEmitter emitter, StoredEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.id())
                .name(event.name())
                .data(event.data()));
    }

    // Why one builder per event type: the history snapshot must send the very same events as the live stream (RF-01).
    private static StoredEvent assetEvent(ResponseClient post) {
        return new StoredEvent(post.messageId(), "asset.created", post);
    }

    private static StoredEvent packageEvent(PackageCompleted completed) {
        PackageCompletedEvent data = new PackageCompletedEvent(
                "package.completed",
                completed.batchId(),
                new Payload(completed.assetsCount(), completed.parUrlBase(), completed.expiresAt()));
        return new StoredEvent(completed.batchId(), "package.completed", data);
    }

    @Override
    public void publishPackage(PackageCompleted packageCompleted) {

        StoredEvent event = packageEvent(packageCompleted);

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

    // Counts of the initial block, sent in snapshot.completed.
    private record InitialBlock(int packages, int pending) {}

    private record SnapshotCompleted(String type, int packages, int pending) {}

    private record MessageRevisedEvent(String type, String batchId, RevisionPayload payload) {}

    private record RevisionPayload(String messageId, int versionMessage, ApprovalStatus approved, boolean revised) {}

    private record PackageCompletedEvent(String type, String batchId, Payload payload){

    }

    private record Payload(int assetsCount, String parUrlBase, Instant expiresAt){

    }
}
