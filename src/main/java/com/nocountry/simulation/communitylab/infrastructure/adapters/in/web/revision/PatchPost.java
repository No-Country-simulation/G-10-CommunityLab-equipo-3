package com.nocountry.simulation.communitylab.infrastructure.adapters.in.web.revision;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.infrastructure.dto.revision.RevisePostAcceptedResponse;
import com.nocountry.simulation.communitylab.infrastructure.dto.revision.RevisePostRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
                    + "Currently accepted but not stored yet (202); storing and versioning arrive later.")
    @ApiResponse(responseCode = "202", description = "Revision received, not stored yet.")
    @ApiResponse(responseCode = "400", description = "Invalid body, non-editable field or no change.")
    @PatchMapping(value = "/api/v1/{source:discord|telegram}/packages/{batchId}/messages/{messageId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RevisePostAcceptedResponse revise(
            @Parameter(description = "Source stream of the post.", schema = @Schema(allowableValues = {"discord", "telegram"}))
            @PathVariable String source,
            @PathVariable String batchId,
            @PathVariable String messageId,
            @Valid @RequestBody RevisePostRequest request) {
        // The mapping regex already limits source to discord|telegram, so valueOf cannot fail here.
        Source origin = Source.valueOf(source.toUpperCase(Locale.ROOT));
        revisePostUseCase.revise(new RevisePostCommand(
                origin, batchId, messageId, request.expectedVersion(), request.toChange()));
        return new RevisePostAcceptedResponse(batchId, messageId);
        }
}
