package com.nocountry.simulation.communitylab.infrastructure.adapters.out.index;

import com.nocountry.simulation.communitylab.application.dtos.PackageCompleted;
import com.nocountry.simulation.communitylab.application.port.out.PackageIndex;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RedisPackageIndexAdapter implements PackageIndex {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPackageIndexAdapter(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(PackageCompleted completed) {
        try{
            String json = objectMapper.writeValueAsString(completed);
            hash().put(key(completed.source()), completed.batchId(), json);
        }catch (JacksonException | DataAccessException e){
            log.warn("package index write failed: batchId={} source={} cause={}",
                    completed.batchId(), completed.source(), e.getClass().getSimpleName());
        }
    }

    @Override
    public List<PackageCompleted> active(Source source, Instant now) {
        try{
            Map<String, String> entries = hash().entries(key(source));
            List<PackageCompleted> active = new ArrayList<>();
            for(Map.Entry<String, String> entry : entries.entrySet()){
                PackageCompleted completed;
                try{
                    completed = objectMapper.readValue(entry.getValue(), PackageCompleted.class);

                    if(completed.expiresAt().isAfter(now)){
                        active.add(completed);
                    } else {
                        hash().delete(key(source), entry.getKey());
                    }
                }catch (JacksonException e){
                    log.warn("package index read failed: batchId={} source={} ",
                            entry.getKey(), source);
                }
            }
            active.sort(Comparator.comparing(PackageCompleted::expiresAt));
            return active;
        }catch (DataAccessException e){
            log.warn("package index read failed: source={} cause={}", source, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String key (Source source){
        return "packages:" + source.name();
    }

    private HashOperations<String, String, String> hash(){
        return redisTemplate.opsForHash();
    }
}
