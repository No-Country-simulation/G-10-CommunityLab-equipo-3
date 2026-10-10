package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;

public interface ArtifactStore {
    StoreResult save (AssetPackage assetPackage);
}
