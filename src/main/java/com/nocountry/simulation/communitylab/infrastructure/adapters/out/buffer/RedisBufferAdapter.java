package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
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

            Long result = redisTemplate.opsForSet().add(key(source, CURRENT_IDS), messageProcessed.messageId());

            // Validate if already exists
            if(result != null && result == 1L){
                redisTemplate.opsForList().rightPush(key(source, CURRENT_LIST), json);
                Long totalBytes = redisTemplate.opsForValue().increment(key(source, CURRENT_BYTES), size);
                log.debug("buffer appended: messageId={} batchId={} sizeBytes={}",
                        messageProcessed.messageId(), messageProcessed.messageBatchId(), size);
                return totalBytes == null ? 0L : totalBytes;
            } else {
                log.debug("buffer deduped: messageId={}", messageProcessed.messageId());
                return 0L;
            }
        } catch (JacksonException e) {
            log.warn("buffer serialize failed: messageId={}", messageProcessed.messageId());
            return 0L;
        } catch (RedisConnectionFailureException e){
            log.warn("buffer redis down: messageId={}", messageProcessed.messageId());
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
            if(jsons == null)
                return List.of();
            List<EnrichedComment> messages = new ArrayList<>();

            for(String json : jsons){
                try{
                    messages.add(objectMapper.readValue(json, EnrichedComment.class));
                } catch (JacksonException | InvalidAssetException e){
                    log.warn("sealed record skipped: batchId={} source={}", batchId, source);
                }
            }
            return messages;
        } catch (RedisConnectionFailureException e){
        return List.of();
        }
    }

    @Override
    public void discardSealed(Source source, String batchId) {
        try {
            redisTemplate.delete(key(source, SEALED + batchId));
            redisTemplate.opsForSet().remove(key(source, PENDING), batchId);
        } catch (RedisConnectionFailureException e) {
            log.warn("buffer redis down: discard batchId={} source={}", batchId, source);
        }
    }
}
