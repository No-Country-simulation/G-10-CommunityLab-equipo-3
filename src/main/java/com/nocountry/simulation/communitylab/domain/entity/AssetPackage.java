package com.nocountry.simulation.communitylab.domain.entity;

import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;

import java.time.Instant;
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

    // Relative object name; the infrastructure adds the bucket prefix (paquetes/).
    public String objectName() {
        return source.name().toLowerCase(Locale.ROOT) + "/paquete-" + batchId + ".json";
    }
}
