package com.nocountry.simulation.communitylab.application.services.ai;

import com.nocountry.simulation.communitylab.application.dtos.RequestToLLM;
import com.nocountry.simulation.communitylab.application.event.IngestAcceptedEvent;
import com.nocountry.simulation.communitylab.application.port.out.BufferPort;
import com.nocountry.simulation.communitylab.application.port.out.EnrichedCommentFromAi;
import com.nocountry.simulation.communitylab.application.port.out.RequestToLLMProcess;
import com.nocountry.simulation.communitylab.domain.entity.Comment;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.entity.ResponseModel;
import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnrichmentListener {

    private final RequestToLLMProcess llm;
    private final EnrichedCommentFromAi converter;
    private final BufferPort buffer;


    @Async
    @EventListener
    public void on(IngestAcceptedEvent event){
        try{
            if(event.message() == null || event.message().content().isEmpty())
                throw new InvalidCommentException("La solicitud no es valida");

            String message = event.message().content();
            ResponseModel response;
            try {
                response = llm.processMessage(new RequestToLLM(message));
            } catch (Exception llmFailure) {
                // LLM failure leaves a flagged trace, never silent loss.
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

            buffer.appendToBatch(messageProcessed);
        } catch (Exception e){
            return;
        }
    }
}
