package com.nocountry.simulation.communitylab.application.services.storage;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.port.in.PackageRunUseCase;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class PackageRunService implements PackageRunUseCase {

    private final BufferPort buffer;
    private final ArtifactStore store;
    private final long maxBytes;
    private final long minMessages;
    private final Map<Source, ReentrantLock> locks = new EnumMap<>(Source.class);

    public PackageRunService(BufferPort buffer,
                             ArtifactStore store,
                             @Value("${redis.buffer.max.bytes:921600}") long maxBytes,
                             @Value("${buffer.flush.min-messages:5}") long minMessages) {
        this.buffer = buffer;
        this.store = store;
        this.maxBytes = maxBytes;
        this.minMessages = minMessages;
        for (Source source : Source.values()){
            locks.put(source, new ReentrantLock());
        }
    }

    @Override
    public void flushIfFull(Source source, long currentBytes) {
        if(currentBytes >= maxBytes) {
            flush(source, true);
        }
    }

    @Override
    public void flushScheduled() {
        for (Source source : Source.values()) {
            try {
                boolean enoughMessages = buffer.countMessages(source) >= minMessages;
                flush(source, enoughMessages);
            } catch (RuntimeException e) {
                log.warn("Error flushing scheduled batches", e);
            }
        }
    }

    private void flush(Source source, boolean sealCurrent){
        ReentrantLock lock = locks.get(source);

        if(!lock.tryLock()){
            return;
        }
        try {
            for (String pendingBatchId : buffer.pendingBatches(source)) {
                upload(source, pendingBatchId);
            }
            if (sealCurrent) {
                buffer.sealCurrentBatch(source).ifPresent(batchId -> upload(source, batchId));
            }
        } finally {
            lock.unlock();
        }
    }

    private void upload(Source source, String batchId) {
        List<EnrichedComment> records = buffer.readSealed(source, batchId);
        if (records.isEmpty()) {
            // Why keep it: empty may mean Redis down, never drop data on uncertainty.
            log.warn("sealed batch empty or unreadable: batchId={} source={}", batchId, source);
            return;
        }
        try {
            StoreResult result = store.save(AssetPackage.of(batchId, source, records, Instant.now()));
            if (result.ok()) {
                buffer.discardSealed(source, batchId);
                log.info("package stored: batchId={} source={} records={}", batchId, source, records.size());
            } else {
                log.warn("package upload pending: batchId={} source={}", batchId, source);
            }
        } catch (RuntimeException e) {
            // Why ids only (Q3): no content/author in logs.
            log.warn("package upload failed: batchId={} source={} cause={}",
                    batchId, source, e.getClass().getSimpleName());
        }
    }
}
