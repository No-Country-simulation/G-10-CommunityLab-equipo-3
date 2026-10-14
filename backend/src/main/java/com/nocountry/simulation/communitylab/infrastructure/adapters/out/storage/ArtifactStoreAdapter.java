package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
@ConditionalOnExpression("'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class ArtifactStoreAdapter implements ArtifactStore {

    @Override
    public StoreResult save(AssetPackage assetPackage) {
        log.warn("OCI_NOT_CONFIGURED: batchId={} source={}", assetPackage.batchId(), assetPackage.source());
        return StoreResult.failed();
    }

    // Why throw and not Optional.empty(): empty means "no such package" (404); here there is no store at all (503).
    @Override
    public Optional<StoredPackage> load(Source source, String batchId) {
        log.warn("OCI_NOT_CONFIGURED: revision load batchId={} source={}", batchId, source);
        throw new StorageNotConfiguredException("OCI_NOT_CONFIGURED");
    }

    @Override
    public ReplaceResult replace(AssetPackage assetPackage, String etag) {
        log.warn("OCI_NOT_CONFIGURED: revision replace batchId={} source={}", assetPackage.batchId(), assetPackage.source());
        throw new StorageNotConfiguredException("OCI_NOT_CONFIGURED");
    }
}
