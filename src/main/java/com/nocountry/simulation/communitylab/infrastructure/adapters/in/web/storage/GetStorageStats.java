package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.storage;

import com.nocountry.simulation.communitylab.application.dtos.StorageStats;
import com.nocountry.simulation.communitylab.application.port.in.GetStorageStatsUseCase;
import com.nocountry.simulation.communitylab.infrastructure.config.storage.StorageStatsProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Storage", description = "Object Storage usage against the free tier (no auth in Phase 1).")
@RestController
@RequiredArgsConstructor
public class GetStorageStats {

    private final GetStorageStatsUseCase getStorageStatsUseCase;
    private final StorageStatsProperties properties;

    @Operation(
            summary = "Object Storage usage",
            description = "Bucket usage from OCI Monitoring: stored bytes of the Standard tier (previous object versions "
                    + "included) and object versions, plus requests to the bucket since the 1st of the current month "
                    + "(00:00 UTC), each with its free-tier limit. The frontend renders used / limit. "
                    + "Requests lag ~5 min; storage is published hourly (measuredAt). The backend asks OCI at most once "
                    + "per cache TTL for every client; stale=true means OCI failed and this is the previous snapshot.")
    @ApiResponse(responseCode = "200", description = "Current snapshot, or the previous one with stale=true.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StorageStats.class),
                    examples = @ExampleObject(name = "snapshot", value = """
                            {
                              "storage": {
                                "tier": "STANDARD",
                                "usedBytes": 2684354560,
                                "limitBytes": 10737418240,
                                "objectCount": 312,
                                "measuredAt": "2026-10-14T15:00:00Z"
                              },
                              "requests": {
                                "periodStart": "2026-10-01T00:00:00Z",
                                "limit": 50000,
                                "total": 1840, "get": 1710, "put": 96, "list": 0,
                                "head": 4, "delete": 0, "clientErrors": 30
                              },
                              "generatedAt": "2026-10-14T15:42:07Z",
                              "stale": false
                            }""")))
    @ApiResponse(responseCode = "503",
            description = "OCI Monitoring failed and there is no previous snapshot, or OCI_NOT_CONFIGURED.")
    @GetMapping(value = "/api/v1/storage/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StorageStats> getStorageStats() {
        // Why the cache TTL: browsers must not ask more often than the backend refreshes, nor keep a snapshot longer.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(properties.cacheTtl()).cachePublic())
                .body(getStorageStatsUseCase.get());
    }
}
