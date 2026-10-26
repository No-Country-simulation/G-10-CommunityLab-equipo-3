package com.nocountry.simulation.communitylab.infrastructure.adapters.out.cache;

import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

// Copy of a package that OCI already confirmed (spec 010 RF-08): only the storage adapter writes it, and only after a
// successful revision GET or If-Match PUT, so Redis never holds a package the bucket doesn't. Best effort: any failure
// falls back to OCI.
@Component
@Slf4j
public class RedisPackageCache {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisPackageCache(RedisTemplate<String, String> redisTemplate,
                             ObjectMapper objectMapper,
                             @Value("${storage.package-cache-ttl:24h}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("PACKAGE_CACHE_TTL must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<StoredPackage> get(Source source, String batchId) {
        try {
            String json = redisTemplate.opsForValue().get(key(source, batchId));
            if (json == null) {
                return Optional.empty();
            }
            CachedPackage cached = objectMapper.readValue(json, CachedPackage.class);
            return Optional.of(new StoredPackage(cached.pkg(), cached.etag()));
        } catch (RuntimeException e) {
            // Why any RuntimeException: domain validation runs while deserializing (InvalidAssetException), so an entry
            // written by an older deploy can fail on rules that changed; a cache must fall back to OCI, never break a PATCH.
            // Why ids only (Q3): the cached JSON holds authorName and content
            log.warn("package cache read failed: batchId={} source={} cause={}",
                    batchId, source, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // After a confirmed If-Match PUT: this is the newest version, so it replaces whatever is there.
    public void put(AssetPackage pkg, String etag) {
        write(pkg, etag, false);
    }

    // After a GET: only fills an empty slot. Why NX: a slow GET may finish after a concurrent replace() cached the newer
    // version, and a plain SET would put the older one back (spec 010 RF-08).
    public void putIfAbsent(AssetPackage pkg, String etag) {
        write(pkg, etag, true);
    }

    private void write(AssetPackage pkg, String etag, boolean onlyIfAbsent) {
        // Why skip without etag: a later If-Match needs it, a copy without one could never be written back
        if (etag == null || etag.isBlank()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(new CachedPackage(etag, pkg));
            String key = key(pkg.source(), pkg.batchId());
            if (onlyIfAbsent) {
                redisTemplate.opsForValue().setIfAbsent(key, json, ttl);
            } else {
                redisTemplate.opsForValue().set(key, json, ttl);
            }
        } catch (RuntimeException e) {
            log.warn("package cache write failed: batchId={} source={} cause={}",
                    pkg.batchId(), pkg.source(), e.getClass().getSimpleName());
        }
    }

    public void evict(Source source, String batchId) {
        try {
            redisTemplate.delete(key(source, batchId));
        } catch (RuntimeException e) {
            log.warn("package cache evict failed: batchId={} source={} cause={}",
                    batchId, source, e.getClass().getSimpleName());
        }
    }

    private static String key(Source source, String batchId) {
        return "pkgcache:" + source.name() + ":" + batchId;
    }

    record CachedPackage(String etag, AssetPackage pkg) {}
}
