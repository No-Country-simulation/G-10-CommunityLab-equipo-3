package com.nocountry.simulation.communitylab.application.services.comment;

import com.nocountry.simulation.communitylab.application.port.out.EnrichedCommentFromAi;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.policy.RelevancePolicy;
import org.springframework.stereotype.Service;

@Service
public class ConvertEnrichedCommentService implements EnrichedCommentFromAi {

    @Override
    public EnrichedComment constructMessage(Comment comment, ResponseModel responseFromModel, String batchId) {
        boolean failed = responseFromModel.messageAuthor() == null;
        int relevance = failed ? 0 : RelevancePolicy.score(
                responseFromModel.messageType(),
                responseFromModel.sentiment(),
                comment.content().length(),
                comment.truncated(),
                responseFromModel.relevance());
        return new EnrichedComment(
                batchId,
                comment.messageId(),
                comment.channelId(),
                comment.authorId(),
                comment.authorName(),
                responseFromModel.messageAuthor(),
                responseFromModel.sentiment(),
                responseFromModel.language(),
                responseFromModel.messageType(),
                responseFromModel.topics(),
                relevance,
                failed ? EnrichedComment.LLM_FALLBACK : null,
                EnrichedComment.PROMPT_VERSION,
                comment.sentTime(),
                comment.source(),
                responseFromModel.channelPost(),
                responseFromModel.titlePost(),
                responseFromModel.outputContentProcessed(),
                responseFromModel.hashtags(),
                responseFromModel.cta(),
                false,
                1);
    }
}
