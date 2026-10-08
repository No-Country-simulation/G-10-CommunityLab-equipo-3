package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;

@Component
@Slf4j
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
@RequiredArgsConstructor
public class OciObjectStorageAdapter implements ArtifactStore {

    private final ObjectStorage objectStorage;
    private final OciProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public StoreResult save(AssetPackage assetPackage) {
        String objectKey = properties.prefix() + assetPackage.objectName();
        byte[] bytes = objectMapper.writeValueAsBytes(assetPackage);
        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(properties.namespace())
                .bucketName(properties.bucket())
                .objectName(objectKey)
                .contentType("application/json")
                .contentLength((long)bytes.length)
                .ifNoneMatch("*")
                .putObjectBody(new ByteArrayInputStream(bytes))
                .build();

        try{
            PutObjectResponse response = objectStorage.putObject(request);
            log.info("package stored in OCI: batchId={} source={} object={} bytes={} etag={}",
                    assetPackage.batchId(), assetPackage.source(), objectKey, bytes.length, response.getETag());
            return StoreResult.stored();
        }catch (BmcException e){
            if(e.getStatusCode() == 412){
                log.info("package already in OCI (deduped): batchId={} source={} object={}",
                        assetPackage.batchId(), assetPackage.source(), objectKey);
                return StoreResult.alreadyStored();
            }
            // Why no exception object: status + serviceCode identify the failing layer
            // (401 NotAuthenticated = session, 404 BucketNotFound = bucket/IAM) without dumping URLs every retry.
            log.warn("package upload to OCI failed: batchId={} source={} status={} serviceCode={}",
                    assetPackage.batchId(), assetPackage.source(), e.getStatusCode(), e.getServiceCode());
            return StoreResult.failed();
        }
    }
}
