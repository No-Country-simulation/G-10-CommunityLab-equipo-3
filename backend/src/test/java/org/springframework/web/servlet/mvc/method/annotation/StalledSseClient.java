package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.http.MediaType;

/**
 * Test-only stand-in for a dashboard that stopped reading: its heartbeat write blocks like a full TCP buffer would.
 * Why in Spring's package: {@code ResponseBodyEmitter.Handler} is package-private, and MockMvc never blocks a write.
 */
public final class StalledSseClient {

    private final CountDownLatch writing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger heartbeatWrites = new AtomicInteger();

    public static StalledSseClient attachTo(SseEmitter emitter) throws IOException {
        StalledSseClient client = new StalledSseClient();
        emitter.initialize(client.new BlockingHandler());
        return client;
    }

    public boolean awaitBlockedWrite(long timeout, TimeUnit unit) throws InterruptedException {
        return writing.await(timeout, unit);
    }

    public void release() {
        release.countDown();
    }

    public int heartbeatWrites() {
        return heartbeatWrites.get();
    }

    private final class BlockingHandler implements ResponseBodyEmitter.Handler {

        @Override
        public void send(Object data, MediaType mediaType) {
        }

        @Override
        public void send(Set<ResponseBodyEmitter.DataWithMediaType> items) {
            boolean heartbeat = items.stream().anyMatch(item -> String.valueOf(item.getData()).contains("heartbeat"));
            if (!heartbeat) {
                return;
            }
            heartbeatWrites.incrementAndGet();
            writing.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable failure) {
        }

        @Override
        public void onTimeout(Runnable callback) {
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
        }

        @Override
        public void onCompletion(Runnable callback) {
        }
    }
}
