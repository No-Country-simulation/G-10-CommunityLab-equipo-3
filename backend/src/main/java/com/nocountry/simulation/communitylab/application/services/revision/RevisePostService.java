package com.nocountry.simulation.communitylab.application.services.revision;

import com.nocountry.simulation.communitylab.application.command.RevisePostCommand;
import com.nocountry.simulation.communitylab.application.dtos.MessageRevised;
import com.nocountry.simulation.communitylab.application.dtos.ReplaceResult;
import com.nocountry.simulation.communitylab.application.dtos.RevisionResult;
import com.nocountry.simulation.communitylab.application.dtos.StoredPackage;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.port.in.RevisePostUseCase;
import com.nocountry.simulation.communitylab.application.port.out.ArtifactStore;
import com.nocountry.simulation.communitylab.application.port.out.EventPublishPost;
import com.nocountry.simulation.communitylab.domain.entity.AssetPackage;
import com.nocountry.simulation.communitylab.domain.entity.EnrichedComment;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;
import com.nocountry.simulation.communitylab.domain.exception.PostVersionConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RevisePostService implements RevisePostUseCase {

    // Why 3: one attempt + 2 retries on a concurrent write (spec 007 RF-06).
    private static final int MAX_ATTEMPTS = 3;

    private final ArtifactStore artifactStore;
    private final EventPublishPost eventPublishPost;

    private static EnrichedComment findPost(AssetPackage assetPackage, String messageId){
        return assetPackage.enriched().stream()
                .filter(post -> post.messageId().equals(messageId))
                .findFirst()
                .orElseThrow(() -> new PostNotFoundException("post not found in package"));
    }

    @Override
    public RevisionResult revise(RevisePostCommand command) {
        // Why a loop: a CONFLICT means another write changed the object, so re-read it (fresh etag) and retry.
        for(int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            StoredPackage stored = artifactStore.load(command.source(), command.batchId())
                    .orElseThrow(() -> new PostNotFoundException("package not found"));
            EnrichedComment post = findPost(stored.pkg(), command.messageId());

            // Why re-read: versions only go up, so a client ahead of what was read means a stale copy (spec 010 RF-09)
            if(post.versionMessage() < command.expectedVersion()){
                stored = artifactStore.loadFresh(command.source(), command.batchId())
                        .orElseThrow(() -> new PostNotFoundException("package not found"));
                post = findPost(stored.pkg(), command.messageId());
            }
            AssetPackage pack = stored.pkg();

            if(post.versionMessage() != command.expectedVersion()){
                throw new PostVersionConflictException("post was revised meanwhile");
            }

            AssetPackage revised = pack.revise(command.messageId(), command.change());

            if(revised == pack){
                log.info("revision without changes: source={} batchId={} messageId={} versionMessage={}",
                        command.source(), command.batchId(), command.messageId(), post.versionMessage());

                return new RevisionResult(
                        command.batchId(),
                        command.messageId(),
                        post.versionMessage(),
                        post.approved(),
                        post.revised(),
                        false
                );
            }

            EnrichedComment updated = findPost(revised, command.messageId());
            ReplaceResult replaceResult = artifactStore.replace(revised, stored.etag());

            switch (replaceResult) {
                case REPLACED -> {
                    eventPublishPost.publishRevision(new MessageRevised(command.source(), command.batchId(),
                            command.messageId(), updated.versionMessage(), updated.approved(), updated.revised()));
                    log.info("post revised: source={} batchId={} messageId={} versionMessage={}",
                            command.source(), command.batchId(), command.messageId(), updated.versionMessage());
                    return new RevisionResult(command.batchId(), command.messageId(),
                            updated.versionMessage(), updated.approved(), updated.revised(), true);
                }
                case CONFLICT -> log.info("revision conflict, retrying: source={} batchId={} messageId={} attempt={}",
                        command.source(), command.batchId(), command.messageId(), attempt);
                case FAILED -> throw new StorageFailureException("package rewrite failed");
            }
        }

        throw new PostVersionConflictException("package kept changing after retries");
    }
}
