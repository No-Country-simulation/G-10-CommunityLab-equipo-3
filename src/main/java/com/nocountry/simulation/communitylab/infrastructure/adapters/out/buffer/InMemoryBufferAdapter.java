package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;

/**
 * Local in-memory buffer unblocking 001 without Redis.
 * TODO(004): replace with RedisBufferAdapter (LIST+SET+bytes, flush 900KB, DEL + new uuid).
 */
@Component
public class InMemoryBufferAdapter implements BufferPort {

    // Why volatile: JDA calls ingest() concurrently, readers must see the open lot.
    private volatile String currentBatchId = UUID.randomUUID().toString();

    // Why concurrent: dedup by native messageId without blocking ingest p95<300ms.
    private final Set<String> seenIds = ConcurrentHashMap.newKeySet();
    private final List<ChannelMessage> messages =
            Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong bytes = new AtomicLong(0);

    @Override
    public String getCurrentBatchId() {
        return currentBatchId;
    }

    public boolean append(ChannelMessage message) {
        if (message == null || message.messageId() == null) {
            return false;
        }
        if (!seenIds.add(message.messageId())) {
            return false;
        }
        messages.add(message);
        String content = message.content();
        if (content != null) {
            bytes.addAndGet(content.getBytes(StandardCharsets.UTF_8).length);
        }
        return true;
    }

    /** Snapshot copy for future flush without holding the lock. */
    public List<ChannelMessage> snapshot() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    public long byteSize() {
        return bytes.get();
    }

    public int size() {
        return messages.size();
    }

    public synchronized String rotate() {
        String next = UUID.randomUUID().toString();
        currentBatchId = next;
        seenIds.clear();
        synchronized (messages) {
            messages.clear();
        }
        bytes.set(0);
        return next;
    }
}
