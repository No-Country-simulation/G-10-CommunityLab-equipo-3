package com.nocountry.simulation.communitylab.application.services.ai;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.EnrichedCommentFromAi;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import com.nocountry.simulation.communitylab.domain.policy.HallucinationGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnrichmentListener {

    private final RequestToLLMProcess llm;
    private final EnrichedCommentFromAi converter;
    private final BufferPort buffer;
    private final EventPublishPost eventPublishPost;


    @Async
    @EventListener
    public void on(IngestAcceptedEvent event){
        try{
            if(event.message() == null || event.message().content().isEmpty())
                throw new InvalidCommentException("La solicitud no es valida");

            String message = event.message().content();
            // Why ids only (Q3): trace line greppable by messageId, no PII.
            log.info("pipeline start: messageId={} batchId={}",
                    event.message().messageId(), event.message().batchId());
            ResponseModel response;
            try {
                response = llm.processMessage(new RequestToLLM(message));
            } catch (Exception llmFailure) {
                // LLM failure leaves a flagged trace, never silent loss.
                log.warn("llm fallback: messageId={} batchId={} cause={}",
                        event.message().messageId(), event.message().batchId(),
                        llmFailure.getClass().getSimpleName());
                response = ResponseModel.fallback();
            }
            Comment comment = Comment.create(
                    event.message().messageId(),
                    event.message().channelId(),
                    event.message().authorId(),
                    event.message().authorName(),
                    event.message().content(),
                    event.message().sentTime(),
                    event.message().source()
            );

            EnrichedComment messageProcessed = converter.constructMessage(
                    comment,
                    response,
                    event.message().batchId()
            );

            // Every record goes through the Guard (single choke point);
            // LLM_FALLBACK traces are approved inside it as auditable records.
            if (!HallucinationGuard.passes(
                    comment.content(), messageProcessed.copy(), messageProcessed.flag())) {
                // Why ids only (Q3): no authorId/authorName/content/tokens in prod logs.
                log.warn("Guard blocked content: messageId={} batchId={} source={}",
                        comment.messageId(), event.message().batchId(), comment.source());
                return;
            }

            // Why flag-first equals + fallback skip: flag/contentProcessed can be null
            // (NPE here silently dropped every valid post and every fallback trace).
            boolean fallback = EnrichedComment.LLM_FALLBACK.equals(messageProcessed.flag());
            if (!fallback && (messageProcessed.contentProcessed() == null
                    || messageProcessed.contentProcessed().isBlank()
                    || messageProcessed.hashtags().isEmpty()
                    || messageProcessed.copy() == null
                    || messageProcessed.copy().isBlank())) {
                log.warn("Post invalid: messageId={} batchId={} source={}",
                        comment.messageId(), event.message().batchId(), comment.source());
                return;
            }

            // Sent message to client
            eventPublishPost.publish(messageProcessed);

            // Storage message in Redis
            buffer.appendToBatch(messageProcessed);

            log.info("pipeline done: messageId={} batchId={} channelPost={} flag={}",
                    comment.messageId(), event.message().batchId(),
                    messageProcessed.channelPost(), messageProcessed.flag());
        } catch (Exception e){
            return;
        }
    }
}
