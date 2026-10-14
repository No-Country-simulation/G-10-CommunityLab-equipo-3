package com.nocountry.simulation.communitylab.infrastructure.dto.revision;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nocountry.simulation.communitylab.domain.entity.PostChange;
import com.nocountry.simulation.communitylab.domain.enums.Channels;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

public record RevisePostRequest(
        Boolean revised,
        @NotNull @Min(1) Integer expectedVersion,
        Boolean approved,
        @Size(max = 5) List<@NotBlank @Size(max = 50) String> topics,
        Channels channelPost,
        @Size(max = 200) String titlePost,
        @Size(max = 4000) String outputContentProcessed,
        @Size(max = 5) List<@NotBlank @Size(max = 50) String> hashtags,
        @Size(max = 300) String cta,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields
) {
    @JsonIgnore
    @AssertTrue(message = "at least one change is required")
    public boolean isAnyChange() {
        return revised != null || approved != null || topics != null || channelPost != null || titlePost != null
                || outputContentProcessed != null || hashtags != null || cta != null;
    }

    @JsonIgnore
    @AssertTrue(message = "editable fields: approved, revised, topics, channelPost, titlePost, outputContentProcessed, hashtags, cta")
    public boolean isOnlyEditableFields() {
        return unknownFields == null || unknownFields.isEmpty();
    }

    // A review can't be undone (spec 007 RF-02): revised only accepts true.
    @JsonIgnore
    @AssertTrue(message = "revised only accepts true")
    public boolean isRevisedOnlyTrue() {
        return revised == null || revised;
    }

    public PostChange toChange() {
        return new PostChange(revised, approved, topics, channelPost, titlePost, outputContentProcessed, hashtags, cta);
    }
}
