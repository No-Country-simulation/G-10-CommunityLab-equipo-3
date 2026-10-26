package com.nocountry.simulation.communitylab.infrastructure.adapters.out.storage;

import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.StoreResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.adapters.out.cache.RedisPackageCache;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
@ConditionalOnExpression("!'${oci.auth-mode:none}'.equalsIgnoreCase('none')")
public class OciObjectStorageAdapter implements ArtifactStore, DisposableBean {

    private static final int MAX_OBJECT_BYTES = 1_048_576;
    private static final int PRECONDITION_FAILED = 412;
    private static final int NOT_FOUND = 404;
    // Why 3: both sources may flush at once (one per-source lock each), and a PATCH must still find a free thread
    private static final int SDK_THREADS = 3;

    private final ObjectStorage objectStorage;
    private final OciProperties properties;
    private final ObjectMapper objectMapper;
    private final RedisPackageCache packageCache;
    // Why platform threads (spec 010 RF-06): callers now run on virtual threads, and the SDK's blocking HTTP client pins
    // them on JDK 21; with 2 cores two slow uploads would freeze every virtual thread. The caller parks here instead.
    // How long a call may hold a thread is bounded by the client itself (OciConfig: connect/read timeouts, max attempts).
    private final ExecutorService sdkExecutor = Executors.newFixedThreadPool(SDK_THREADS,
            Thread.ofPlatform().name("oci-storage-", 0).daemon(true).factory());

    public OciObjectStorageAdapter(ObjectStorage objectStorage,
                                   OciProperties properties,
                                   ObjectMapper objectMapper,
                                   RedisPackageCache packageCache) {
        this.objectStorage = objectStorage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.packageCache = packageCache;
    }

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
            PutObjectResponse response = onSdkThread(() -> objectStorage.putObject(request));
            log.info("package stored in OCI: batchId={} source={} object={} bytes={} etag={}",
                    assetPackage.batchId(), assetPackage.source(), objectKey, bytes.length, response.getETag());
            // Why no cache here: most flushed packages are never revised, and up to 900KB each for 24h would sit in the
            // buffer's Redis; the copy is made on the first revision instead (spec 010 RF-08, RNF-06)
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
            PreauthenticatedRequest par = onSdkThread(() -> objectStorage.createPreauthenticatedRequest(request))
                    .getPreauthenticatedRequest();
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

    // Why trust a cache hit without asking OCI: the only writer is this adapter and replace() sends If-Match, so a stale
    // copy ends in 412 -> evict -> fresh read; a hit saves a GET from the monthly quota (spec 010 RF-08).
    @Override
    public Optional<StoredPackage> load(Source source, String batchId) {
        Optional<StoredPackage> cached = packageCache.get(source, batchId);
        if (cached.isPresent()) {
            log.info("package loaded from cache: batchId={} source={}", batchId, source);
            return cached;
        }
        return loadFromBucket(source, batchId);
    }

    @Override
    public Optional<StoredPackage> loadFresh(Source source, String batchId) {
        packageCache.evict(source, batchId);
        return loadFromBucket(source, batchId);
    }

    private Optional<StoredPackage> loadFromBucket(Source source, String batchId) {
        String objectKey = properties.prefix() + AssetPackage.objectName(source, batchId);

        GetObjectRequest request = GetObjectRequest.builder()
                .namespaceName(properties.namespace())
                .bucketName(properties.bucket())
                .objectName(objectKey)
                .build();

        FetchedObject fetched;
        try{
            fetched = onSdkThread(() -> fetch(request));
        } catch (BmcException e){
            if (e.getStatusCode() == NOT_FOUND) {
                return Optional.empty();
            }
            log.warn("package load from OCI failed: batchId={} source={} status={} serviceCode={}",
                    batchId, source, e.getStatusCode(), e.getServiceCode());
            throw new StorageFailureException("package load failed");
        } catch (UncheckedIOException e) {
            log.warn("package in OCI is unreadable: batchId={} source={}", batchId, source);
            throw new StorageFailureException("package unreadable");
        }

        if(fetched.body() == null){
            // Why ERROR: the flush closes at 900KB, so a stored package this big only exists through a bug.
            log.error("package over the 1MB budget, not revisable: batchId={} source={} bytes={}",
                    batchId, source, fetched.contentLength());
            throw new PackageTooLargeException("package over the 1MB budget");
        }

        try{
            AssetPackage assetPackage = objectMapper.readValue(fetched.body(), AssetPackage.class);
            log.info("package loaded from OCI: batchId={} source={}", batchId, source);
            packageCache.putIfAbsent(assetPackage, fetched.etag());
            return Optional.of(new StoredPackage(assetPackage, fetched.etag()));
        } catch (JacksonException e){
            log.warn("package in OCI is unreadable: batchId={} source={}", batchId, source);
            throw new StorageFailureException("package unreadable");
        }
    }

    // Runs on the SDK thread: the body is read there too, because reading the stream is the same blocking client.
    private FetchedObject fetch(GetObjectRequest request) {
        GetObjectResponse response = objectStorage.getObject(request);
        try(InputStream body = response.getInputStream()){
            Long length = response.getContentLength();
            if(length != null && length >= MAX_OBJECT_BYTES){
                return new FetchedObject(null, response.getETag(), length);
            }
            // Why a capped read: without Content-Length the header check above can't run, so never read past the budget
            byte[] bytes = body.readNBytes(MAX_OBJECT_BYTES);
            if(bytes.length >= MAX_OBJECT_BYTES){
                return new FetchedObject(null, response.getETag(), length == null ? (long) bytes.length : length);
            }
            return new FetchedObject(bytes, response.getETag(), length);
        } catch (IOException e){
            throw new UncheckedIOException(e);
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

        // Why evict before the PUT: whatever happens next (412, error, timeout, interrupt, a failed put of the new copy)
        // the cache can only end empty or holding the confirmed new version, never the old one
        packageCache.evict(assetPackage.source(), assetPackage.batchId());

        try {
            PutObjectResponse response = onSdkThread(() -> objectStorage.putObject(request));
            log.info("package replaced in OCI: batchId={} source={}", assetPackage.batchId(), assetPackage.source());
            // Why the new etag: the next revision of this package can then skip the GET
            packageCache.put(assetPackage, response.getETag());
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

    @Override
    public void destroy() {
        sdkExecutor.shutdown();
    }

    // Unwraps the task's own exception so BmcException (timeouts included, raised by the client) keeps driving the
    // status-code branches above. Why no wait timeout here: cancelling the Future can't stop the blocking HTTP call,
    // so it reported a failure for a write OCI might still apply and kept the thread busy anyway.
    private <T> T onSdkThread(Callable<T> call) {
        Future<T> future;
        try {
            future = sdkExecutor.submit(call);
        } catch (RejectedExecutionException e) {
            throw new StorageFailureException("storage is shutting down");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StorageFailureException("storage call interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new StorageFailureException("storage call failed");
        }
    }

    // body null = over the 1MB budget, left unread
    private record FetchedObject(byte[] body, String etag, Long contentLength) {}
}
