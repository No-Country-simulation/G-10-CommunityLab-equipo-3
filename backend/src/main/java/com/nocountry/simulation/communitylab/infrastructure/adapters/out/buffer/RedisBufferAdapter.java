package com.nocountry.simulation.communitylab.infrastructure.adapters.out.buffer;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@Slf4j
public class RedisBufferAdapter implements BufferPort {

    private final RedisTemplate<String, String> redisTemplate;
    // Convert text in JSON
    private final ObjectMapper objectMapper;
    // Field reserved for future use -> flush 004
    private final long maxBatchSize;

    // Constants keys
    private static final String uuidLote = "buffer:current:id";
    private static final String ids = "buffer:current:ids";
    private static final String jsonKey= "buffer:current:list";
    private static final String counterSize = "buffer:current:bytes";

    public RedisBufferAdapter(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${redis.buffer.max.bytes:921600}") long maxBatchSize) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public String getCurrentBatchId() {
        try{
            String idBatch = redisTemplate.opsForValue().get(uuidLote);

            // Return a new UUID if the current batch ID is null or empty
            if(idBatch == null || idBatch.isEmpty()) {
                idBatch = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set(uuidLote, idBatch);
            }
            return idBatch;
        } catch (RedisConnectionFailureException e) {
            log.debug("Error getting current batch ID", e);
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public void appendToBatch(ChannelMessage channelMessage) {

        if (channelMessage == null || channelMessage.messageId() == null) {
            log.debug("Channel message is null");
            return;
        }

        try{
            String json = objectMapper.writeValueAsString(channelMessage);

            // Get JSON size in bytes
            int size = json.getBytes(StandardCharsets.UTF_8).length;

            Long result = redisTemplate.opsForSet().add(ids, channelMessage.messageId());

            // Validate if already exists
            if(result != null && result == 1L){
                redisTemplate.opsForList().rightPush(jsonKey, json);
                redisTemplate.opsForValue().increment(counterSize, size);
            }
        } catch (JacksonException e) {
            log.debug("Error processing JSON", e);
            return;
        } catch (RedisConnectionFailureException e){
            log.debug("Error appending to batch", e);
            return;
        }
    }
}
