package com.nocountry.simulation.communitylab.infrastructure.config.jackson;

import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import org.springframework.boot.jackson.JacksonMixin;
import tools.jackson.databind.annotation.JsonDeserialize;

// Why a mixin: the domain stays free of Jackson and only stored records (Redis/OCI) accept the old boolean;
// the PATCH body keeps rejecting approved:true.
@JacksonMixin(EnrichedComment.class)
abstract class EnrichedCommentMixin {

    @JsonDeserialize(using = LegacyApprovalDeserializer.class)
    abstract ApprovalStatus approved();
}
