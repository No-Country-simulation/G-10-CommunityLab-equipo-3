package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.revision;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.dtos.RevisionResult;
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.dto.revision.RevisePostRequest;
import com.nocountry.simulation.communitylab.infrastructure.dto.revision.RevisePostResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@Tag(name = "Revisions", description = "Approve or edit a stored post (no auth in Phase 1).")
@RestController
@RequiredArgsConstructor
public class PatchPost {
    private final RevisePostUseCase revisePostUseCase;

    @Operation(
            summary = "Revise a post",
            description = "Approves and/or edits the editable fields of one post of a stored package. "
                    + "Every saved change bumps versionMessage and Object Storage keeps the previous version. "
                    + "Send the versionMessage you have as expectedVersion; absent fields keep their value, "
                    + "\"\" clears a text and [] clears a list.")
    @ApiResponse(responseCode = "200", description = "Saved: new versionMessage, or the same one if nothing changed.")
    @ApiResponse(responseCode = "400", description = "Invalid body, non-editable field, no change or channel rules broken.")
    @ApiResponse(responseCode = "404", description = "Package or post not found.")
    @ApiResponse(responseCode = "409", description = "expectedVersion is stale or the package kept changing; reload and retry.")
    @ApiResponse(responseCode = "413", description = "Package over the 1MB budget.")
    @ApiResponse(responseCode = "422", description = "LLM_FALLBACK record: it has no post to revise.")
    @ApiResponse(responseCode = "502", description = "Object Storage failed.")
    @ApiResponse(responseCode = "503", description = "OCI_NOT_CONFIGURED: Object Storage is not configured.")
    @PatchMapping(value = "/api/v1/{source:discord|telegram}/packages/{batchId}/messages/{messageId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RevisePostResponse revise(
            @Parameter(description = "Source stream of the post.",
                    schema = @Schema(allowableValues = {"discord", "telegram"}))
            @PathVariable String source,
            @PathVariable String batchId,
            @PathVariable String messageId,
            @Valid @RequestBody RevisePostRequest request) {
        Source origin = Source.valueOf(source.toUpperCase(Locale.ROOT));
        RevisionResult result = revisePostUseCase.revise(new RevisePostCommand(
                origin, batchId, messageId, request.expectedVersion(), request.toChange()));
        return new RevisePostResponse(result.batchId(), result.messageId(), result.versionMessage(), result.approved());
    }
}
