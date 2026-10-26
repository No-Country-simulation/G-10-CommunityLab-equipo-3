package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.enums.Source;

import java.util.Optional;

public interface ArtifactStore {
    StoreResult save (AssetPackage assetPackage);

    // Empty only when the object doesn't exist (404); other failures are exceptions so they can't be read as "not found".
    Optional<StoredPackage> load(Source source, String batchId);

    // Same as load() but skips any cached copy; used when a read looks stale (spec 010 RF-09).
    default Optional<StoredPackage> loadFresh(Source source, String batchId) {
        return load(source, batchId);
    }

    // Rewrites only if the object still has this etag (If-Match), otherwise CONFLICT.
    ReplaceResult replace(AssetPackage assetPackage, String etag);
}
