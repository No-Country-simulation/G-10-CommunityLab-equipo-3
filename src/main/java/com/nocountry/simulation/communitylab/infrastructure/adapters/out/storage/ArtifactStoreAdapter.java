package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnExpression("'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class ArtifactStoreAdapter implements ArtifactStore {

    @Override
    public StoreResult save(AssetPackage assetPackage) {
        log.warn("OCI_NOT_CONFIGURED: batchId={} source={}", assetPackage.batchId(), assetPackage.source());
        return StoreResult.failed();
    }
}
