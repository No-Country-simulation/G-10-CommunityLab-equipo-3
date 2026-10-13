package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.config.oci.OciProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
@Slf4j
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
@RequiredArgsConstructor
public class OciObjectStorageAdapter implements ArtifactStore {

    private static final int MAX_OBJECT_BYTES = 1_048_576;
    private static final int PRECONDITION_FAILED = 412;
    private static final int NOT_FOUND = 404;

    private final ObjectStorage objectStorage;
    private final OciProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public StoreResult save(AssetPackage assetPackage) {
        String objectKey = properties.prefix() + assetPackage.objectName();
        byte[] bytes = objectMapper.writeValueAsBytes(assetPackage);

        if(bytes.length >= MAX_OBJECT_BYTES) {
            log.error("package over the 1MB budget, uploading anyway: batchId={} source={} bytes={} max={}",
                    assetPackage.batchId(), assetPackage.source(), bytes.length, MAX_OBJECT_BYTES);
            // Important not apply return -> Why upload anyway: A sealed batch never shrinks, so refusing it would pin it in Redis forever.
        }

        StoreResult.Status putStatus = putCreateOnly(assetPackage, objectKey, bytes);

        if(putStatus == StoreResult.Status.FAILED) {
            return StoreResult.failed();
        }

        return createReadPar(assetPackage, objectKey, putStatus);
    }

    private StoreResult.Status putCreateOnly(AssetPackage assetPackage, String objectKey, byte[] bytes){
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
            return StoreResult.Status.STORED;
        }catch (BmcException e){
            if(e.getStatusCode() == PRECONDITION_FAILED){
                log.info("package already in OCI (deduped): batchId={} source={} object={}",
                        assetPackage.batchId(), assetPackage.source(), objectKey);
                return StoreResult.Status.ALREADY_STORED;
            }
            // Why no exception object: status + serviceCode identify the failing layer
            // (401 NotAuthenticated = session, 404 BucketNotFound = bucket/IAM) without dumping URLs every retry.
            log.warn("package upload to OCI failed: batchId={} source={} status={} serviceCode={}",
                    assetPackage.batchId(), assetPackage.source(), e.getStatusCode(), e.getServiceCode());
            return StoreResult.Status.FAILED;
        }
    }

    private StoreResult createReadPar(AssetPackage assetPackage, String objectKey, StoreResult.Status putStatus){
        Instant expiresAt = Instant.now().plus(Duration.ofDays(properties.parTtlDays()));

        CreatePreauthenticatedRequestDetails details = CreatePreauthenticatedRequestDetails.builder()
                .name("par-" + assetPackage.batchId())
                .objectName(objectKey)
                .accessType(CreatePreauthenticatedRequestDetails.AccessType.ObjectRead)
                .timeExpires(Date.from(expiresAt))
                .build();

        CreatePreauthenticatedRequestRequest request = CreatePreauthenticatedRequestRequest.builder()
                .namespaceName(properties.namespace())
                .bucketName(properties.bucket())
                .createPreauthenticatedRequestDetails(details)
                .build();

        try{
            PreauthenticatedRequest par = objectStorage.createPreauthenticatedRequest(request).getPreauthenticatedRequest();
            log.info("read PAR created: batchId={} source={} parId={}",
                    assetPackage.batchId(), assetPackage.source(), par.getId());

            return new StoreResult(putStatus, par.getFullPath(), par.getTimeExpires().toInstant());
        } catch (BmcException e) {
            // Why failed even though the object is already in OCI: without a PAR there is nothing to announce.
            // The batch stays pending and the retry gets 412 (no re-upload) and creates the PAR then.
            log.warn("read PAR creation failed: batchId={} source={} status={} serviceCode={}",
                    assetPackage.batchId(), assetPackage.source(), e.getStatusCode(), e.getServiceCode());
            return StoreResult.failed();
        }
    }

    @Override
    public Optional<StoredPackage> load(Source source, String batchId) {
        String objectKey = properties.prefix() + AssetPackage.objectName(source, batchId);

        GetObjectRequest request = GetObjectRequest.builder()
                .namespaceName(properties.namespace())
                .bucketName(properties.bucket())
                .objectName(objectKey)
                .build();

        GetObjectResponse response;
        try{
            response = objectStorage.getObject(request);
        } catch (BmcException e){
            if (e.getStatusCode() == NOT_FOUND) {
                return Optional.empty();
            }
            log.warn("package load from OCI failed: batchId={} source={} status={} serviceCode={}",
                    batchId, source, e.getStatusCode(), e.getServiceCode());
            throw new StorageFailureException("package load failed");
        }

        try(InputStream body = response.getInputStream()){
            if(response.getContentLength() >= MAX_OBJECT_BYTES){
                // Why ERROR: the flush closes at 900KB, so a stored package this big only exists through a bug.
                log.error("package over the 1MB budget, not revisable: batchId={} source={} bytes={}",
                        batchId, source, response.getContentLength());
                throw new PackageTooLargeException("package over the 1MB budget");
            }
            AssetPackage assetPackage = objectMapper.readValue(body, AssetPackage.class);
            log.info("package loaded from OCI: batchId={} source={}", batchId, source);
            return Optional.of(new StoredPackage(assetPackage, response.getETag()));
        } catch (JacksonException | IOException e){
            log.warn("package in OCI is unreadable: batchId={} source={}", batchId, source);
            throw new StorageFailureException("package unreadable");
        }
    }

    @Override
    public ReplaceResult replace(AssetPackage assetPackage, String etag) {
        byte[] bytes = objectMapper.writeValueAsBytes(assetPackage);
        if (bytes.length >= MAX_OBJECT_BYTES) {
            throw new PackageTooLargeException("revised package over the 1MB budget");
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(properties.namespace())
                .bucketName(properties.bucket())
                .objectName(properties.prefix() + assetPackage.objectName())
                .contentType("application/json")
                .contentLength((long) bytes.length)
                .ifMatch(etag)
                .putObjectBody(new ByteArrayInputStream(bytes))
                .build();

        try {
            objectStorage.putObject(request);
            log.info("package replaced in OCI: batchId={} source={}", assetPackage.batchId(), assetPackage.source());
            return ReplaceResult.REPLACED;
        } catch (BmcException e) {
            if (e.getStatusCode() == PRECONDITION_FAILED) {
                log.info("package changed since it was read: batchId={} source={}",
                        assetPackage.batchId(), assetPackage.source());
                return ReplaceResult.CONFLICT;
            }
            log.warn("package replace in OCI failed: batchId={} source={} status={} serviceCode={}",
                    assetPackage.batchId(), assetPackage.source(), e.getStatusCode(), e.getServiceCode());
            return ReplaceResult.FAILED;
        }
    }
}
