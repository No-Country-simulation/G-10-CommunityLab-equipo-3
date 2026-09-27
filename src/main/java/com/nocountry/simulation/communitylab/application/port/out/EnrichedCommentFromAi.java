package com.nocountry.simulation.communitylab.application.port.out;

import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;

public interface EnrichedCommentFromAi {
    EnrichedComment constructMessage(Comment comment, ResponseModel responseFromModel, String batchId);
}
