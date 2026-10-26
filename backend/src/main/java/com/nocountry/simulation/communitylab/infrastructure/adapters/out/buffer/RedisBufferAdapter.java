package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
public class RedisBufferAdapter implements BufferPort {

    private final RedisTemplate<String, String> redisTemplate;
    // Convert text in JSON
    private final ObjectMapper objectMapper;

    // Constants keys
    private static final String CURRENT_ID = "current:id";
    private static final String CURRENT_IDS = "current:ids";
    private static final String CURRENT_LIST= "current:list";
    private static final String CURRENT_BYTES = "current:bytes";
    private static final String PENDING = "pending";
    private static final String SEALED = "sealed:";


    // Why Lua: dedup + push + byte count must be one step; a crash between SADD and RPUSH left the id marked as seen
    // with no record, and every retry was then dropped as a duplicate. Also one round trip instead of three (spec 010 RF-02).
    private static final RedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SADD', KEYS[1], ARGV[1]) == 1 then
              redis.call('RPUSH', KEYS[2], ARGV[2])
              return redis.call('INCRBY', KEYS[3], ARGV[3])
            end
            return 0
            """, Long.class);

    private static String key(Source source, String suffix){
        return "buffer:" + source.name() + ":" + suffix;
    }

    public RedisBufferAdapter(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCurrentBatchId(Source source) {
        String idKey = key(source, CURRENT_ID);
        try{
            String idBatch = redisTemplate.opsForValue().get(idKey);

            // Return a new UUID if the current batch ID is null or empty
            if(idBatch == null || idBatch.isEmpty()) {
                idBatch = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set(idKey, idBatch);
            }
            return idBatch;
        } catch (RedisConnectionFailureException e) {
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public long appendToBatch(EnrichedComment messageProcessed) {

        if (messageProcessed == null || messageProcessed.messageId() == null) {
            return 0L;
        }

        try{
            String json = objectMapper.writeValueAsString(messageProcessed);

            // Extract Source message
            Source source = messageProcessed.source();

            // Get JSON size in bytes
            int size = json.getBytes(StandardCharsets.UTF_8).length;

            Long totalBytes = redisTemplate.execute(APPEND_SCRIPT,
                    List.of(key(source, CURRENT_IDS), key(source, CURRENT_LIST), key(source, CURRENT_BYTES)),
                    messageProcessed.messageId(), json, String.valueOf(size));

            // Why 0 means duplicate: an appended record always adds its own bytes, so the total is never 0
            if(totalBytes != null && totalBytes > 0L){
                log.debug("buffer appended: messageId={} batchId={} sizeBytes={}",
                        messageProcessed.messageId(), messageProcessed.messageBatchId(), size);
                return totalBytes;
            } else {
                log.debug("buffer deduped: messageId={}", messageProcessed.messageId());
                return 0L;
            }
        } catch (JacksonException e) {
            log.warn("buffer serialize failed: messageId={}", messageProcessed.messageId());
            return 0L;
        } catch (DataAccessException e){
            // Why DataAccessException: a timeout (QueryTimeoutException) is not a connection failure but loses the
            // record just the same; it must be logged here instead of escaping the append.
            log.warn("buffer append failed: messageId={} source={} cause={}",
                    messageProcessed.messageId(), messageProcessed.source(), e.getClass().getSimpleName());
            return 0L;
        }
    }

    @Override
    public long countMessages(Source source) {
        try {
            Long size = redisTemplate.opsForList().size(key(source, CURRENT_LIST));
            return size == null ? 0L : size;
        } catch (RedisConnectionFailureException e) {
            return 0L;
        }
    }

    @Override
    public Optional<String> sealCurrentBatch(Source source) {
        try{
            if(countMessages(source) == 0L)
                return Optional.empty();

            String batchId = getCurrentBatchId(source);
            String newBatchId = UUID.randomUUID().toString();

            redisTemplate.execute(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    ops.multi();
                    ops.rename(key(source, CURRENT_LIST), key(source, SEALED + batchId));
                    ops.delete(List.of(key(source, CURRENT_IDS), key(source, CURRENT_BYTES)));
                    ops.opsForValue().set(key(source, CURRENT_ID), newBatchId);
                    ops.opsForSet().add(key(source, PENDING), batchId);
                    return ops.exec();
                }
            });

            return Optional.of(batchId);
        } catch (RedisConnectionFailureException e){
            return Optional.empty();
        }
    }

    @Override
    public Set<String> pendingBatches(Source source) {
        try{
            Set<String> pending = redisTemplate.opsForSet().members(key(source, PENDING));
            return pending == null ? Set.of() : pending;
        } catch (RedisConnectionFailureException e){
            return Set.of();
        }
    }

    @Override
    public List<EnrichedComment> readSealed(Source source, String batchId) {
        try{
            List<String> jsons = redisTemplate.opsForList().range(key(source, SEALED + batchId), 0, -1);
            return deserialize(jsons, source, batchId);
        } catch (RedisConnectionFailureException e){
        return List.of();
        }
    }

    @Override
    public List<EnrichedComment> readSealedAll(Source source, Collection<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = List.copyOf(batchIds);
        try {
            // Why a pipeline: the SSE history used to pay one round trip per pending batch on every reconnect (spec 010 RF-04)
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    for (String batchId : ids) {
                        ops.opsForList().range(key(source, SEALED + batchId), 0, -1);
                    }
                    return null;
                }
            });

            List<EnrichedComment> messages = new ArrayList<>();
            for (int i = 0; i < results.size() && i < ids.size(); i++) {
                if (results.get(i) instanceof List<?> jsons) {
                    messages.addAll(deserialize(jsons, source, ids.get(i)));
                }
            }
            return messages;
        } catch (DataAccessException e) {
            log.warn("sealed batches read failed: source={} cause={}", source, e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public void discardSealed(Source source, String batchId) {
        try {
            // Why MULTI: a crash between both commands left a pending id pointing at a deleted list, logged as empty every tick
            redisTemplate.execute(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    ops.multi();
                    ops.delete(key(source, SEALED + batchId));
                    ops.opsForSet().remove(key(source, PENDING), batchId);
                    return ops.exec();
                }
            });
        } catch (DataAccessException e) {
            log.warn("buffer redis down: discard batchId={} source={}", batchId, source);
        }
    }

    @Override
    public List<EnrichedComment> readOpen(Source source) {
        try{
            List<String> jsons = redisTemplate.opsForList().range(key(source, CURRENT_LIST), 0, -1);
            return deserialize(jsons, source, null);
        }catch(DataAccessException e){
            log.warn("open batch read failed: source={} cause={}", source, e.getClass().getSimpleName());
            return List.of();
        }
    }

    // batchId null = open batch; a corrupt record is skipped so one bad entry never hides the rest
    private List<EnrichedComment> deserialize(List<?> jsons, Source source, String batchId) {
        if (jsons == null) {
            return List.of();
        }
        List<EnrichedComment> messages = new ArrayList<>();
        for (Object json : jsons) {
            try {
                messages.add(objectMapper.readValue(String.valueOf(json), EnrichedComment.class));
            } catch (JacksonException | InvalidAssetException e) {
                if (batchId == null) {
                    log.warn("open record skipped: source={}", source);
                } else {
                    log.warn("sealed record skipped: batchId={} source={}", batchId, source);
                }
            }
        }
        return messages;
    }
}
