package com.nocountry.simulation.communitylab.domain.entity;

import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record AssetPackage(
        String batchId,
        Source source,
        Instant generatedAt,
        List<EnrichedComment> enriched,
        PackageStats stats,
        String promptVersion) {

    public AssetPackage {
        if (batchId == null || batchId.isBlank()) {
            throw new InvalidAssetException("batchId is required");
        }
        if (source == null) {
            throw new InvalidAssetException("source is required");
        }
        if (enriched == null || enriched.isEmpty()) {
            throw new InvalidAssetException("package must contain at least one record");
        }
        enriched = List.copyOf(enriched);
    }

    // Factory: builds stats from the records so callers can't send inconsistent numbers.
    public static AssetPackage of(String batchId, Source source, List<EnrichedComment> enriched, Instant now) {
        int received = enriched == null ? 0 : enriched.size();
        int fallback = enriched == null ? 0 : (int) enriched.stream()
                .filter(e -> EnrichedComment.LLM_FALLBACK.equals(e.flag()))
                .count();
        int assets = received - fallback;
        PackageStats stats = new PackageStats(received, assets, fallback, assets);
        return new AssetPackage(batchId, source, now, enriched, stats, EnrichedComment.PROMPT_VERSION);
    }

    public static String objectName(Source source, String batchId) {
        return source.name().toLowerCase(Locale.ROOT) + "/paquete-" + batchId + ".json";
    }

    public String objectName() {
        return objectName(source, batchId);
    }

    // Revises one post; returns this when nothing changes so the caller can skip the OCI write.
    public AssetPackage revise(String messageId, PostChange change) {
        for (int i = 0; i < enriched.size(); i++) {
            EnrichedComment current = enriched.get(i);
            if (current.messageId().equals(messageId)) {
                EnrichedComment revised = current.revise(change);
                if (revised == current) {
                    return this;
                }
                List<EnrichedComment> records = new ArrayList<>(enriched);
                records.set(i, revised);

                return new AssetPackage(batchId, source, generatedAt, records, stats, promptVersion);
            }
        }
        throw new PostNotFoundException("post not found in package");
    }
}
