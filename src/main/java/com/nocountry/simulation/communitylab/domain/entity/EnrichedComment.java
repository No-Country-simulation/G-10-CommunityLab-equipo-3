package com.nocountry.simulation.communitylab.domain.entity;

import java.time.Instant;
import java.util.List;

import com.nocountry.simulation.communitylab.domain.enums.Channels;
import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.Source;
import com.nocountry.simulation.communitylab.domain.enums.ai.Language;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;

// Structure for buffering in Redis
public record EnrichedComment(
        // Now don't utilize batchIdLote but for storage in Redis it's necessary
        String messageBatchId,
        String messageId,
        String channelId,
        String authorId,
        String authorName,
        String messageAuthor,
        Sentiment sentiment,
        Language language,
        MessageType messageType,
        List<String> topics,
        int relevance,
        String flag,
        String promptVersion,
        Instant sentTime,
        Source source,
        Channels channelPost,
        String titlePost,
        String outputContentProcessed,
        List<String> hashtags,
        String cta,
        Boolean approved,
        Integer versionMessage) {

    public static final String LLM_FALLBACK = "LLM_FALLBACK";

    // Prompt version that produced the analysis.
    public static final String PROMPT_VERSION = "v1";

    // Variables of post bounds per channel
    private static final int LINKEDIN_MIN_CHARS = 80;
    private static final int LINKEDIN_MAX_CHARS = 600;
    private static final int LINKEDIN_MIN_TAGS = 2;
    private static final int LINKEDIN_MAX_TAGS = 5;
    private static final int X_MAX_CHARS = 280;
    private static final int X_MIN_TAGS = 1;
    private static final int X_MAX_TAGS = 2;
    private static final int NEWSLETTER_MIN_WORDS = 100;
    private static final int NEWSLETTER_MAX_WORDS = 400;
    private static final int FAQ_MIN_CHARS = 20;
    private static final int FAQ_MAX_CHARS = 500;
    private static final int MAX_TAGS_OTHER = 5;

    public EnrichedComment {
        boolean fallback = LLM_FALLBACK.equals(flag);

        if (messageId == null || messageId.isBlank()) {
            throw new InvalidAssetException("messageId is required");
        }

        if (!fallback) {
            if (channelPost == null) {
                throw new InvalidAssetException("channelPost is required");
            }
            if (outputContentProcessed == null || outputContentProcessed.isBlank()) {
                throw new InvalidAssetException("outputContentProcessed is required");
            }
        }

        topics = topics == null ? List.of() : List.copyOf(topics);
        relevance = Math.min(100, Math.max(0, relevance));
        hashtags = hashtags == null ? List.of() : List.copyOf(hashtags);
        approved = approved != null && approved;
        versionMessage = versionMessage == null || versionMessage < 1 ? 1 : versionMessage;
        promptVersion = promptVersion == null ? PROMPT_VERSION : promptVersion;
        outputContentProcessed = outputContentProcessed == null ? null : outputContentProcessed.trim();

        // Why "" and not null: one value for "no title / no CTA" whether it comes from the LLM, an old record
        // Each field on its own, before validatePost reads them.
        titlePost = titlePost == null || titlePost.isBlank() ? "" : titlePost;
        cta = cta == null || cta.isBlank() ? "" : cta;

        if (!fallback) {
            validatePost(channelPost, titlePost, outputContentProcessed, hashtags, cta);
        }
    }

    // Applies a dashboard revision; returns this when nothing changes so the caller can skip the OCI write.
    public EnrichedComment revise(PostChange change) {
        if (LLM_FALLBACK.equals(flag)) {
            throw new PostNotRevisableException("LLM_FALLBACK record has no post to revise");
        }
        // Why build before comparing: the constructor normalizes (trim, empty texts), so equals sees final values.
        EnrichedComment candidate = merge(change, versionMessage);
        if (candidate.equals(this)) {
            return this;
        }
        // Every saved update bumps the version, approval included; messageId never changes.
        return merge(change, versionMessage + 1);
    }

    // The constructor re-runs validatePost, so a revision that breaks the final channel's rules can't exist.
    private EnrichedComment merge(PostChange change, int version) {
        return new EnrichedComment(
                messageBatchId, messageId, channelId, authorId, authorName, messageAuthor,
                sentiment, language, messageType,
                orKeep(change.topics(), topics), relevance, flag, promptVersion,
                sentTime, source,
                orKeep(change.channelPost(), channelPost),
                orKeep(change.titlePost(), titlePost),
                orKeep(change.outputContentProcessed(), outputContentProcessed),
                orKeep(change.hashtags(), hashtags),
                orKeep(change.cta(), cta),
                orKeep(change.approved(), approved),
                version);
    }

    // null in PostChange means "not sent": keep the stored value ("" / [] are the way to clear, spec 007 RF-02).
    private static <T> T orKeep(T sent, T current) {
        return sent != null ? sent : current;
    }

    private static void validatePost(Channels channelPost, String titlePost, String outputContentProcessed, List<String> hashtags, String cta) {
        switch (channelPost) {
            case LINKEDIN -> {
                if (outputContentProcessed.length() < LINKEDIN_MIN_CHARS || outputContentProcessed.length() > LINKEDIN_MAX_CHARS) {
                    throw new InvalidAssetException("linkedin outputContentProcessed must be 80..600 chars");
                }
                if (hashtags.size() < LINKEDIN_MIN_TAGS || hashtags.size() > LINKEDIN_MAX_TAGS) {
                    throw new InvalidAssetException("linkedin hashtags must be 2..5");
                }
                if (cta == null || cta.isBlank()) {
                    throw new InvalidAssetException("linkedin cta is required");
                }
            }
            case X -> {
                if (outputContentProcessed.length() > X_MAX_CHARS) {
                    throw new InvalidAssetException("x outputContentProcessed must be <=280 chars");
                }
                if (hashtags.size() < X_MIN_TAGS || hashtags.size() > X_MAX_TAGS) {
                    throw new InvalidAssetException("x hashtags must be 1..2");
                }
            }
            case NEWSLETTER -> {
                int words = wordCount(outputContentProcessed);
                if (words < NEWSLETTER_MIN_WORDS || words > NEWSLETTER_MAX_WORDS) {
                    throw new InvalidAssetException("newsletter outputContentProcessed must be 100..400 words");
                }
                if (hashtags.size() > MAX_TAGS_OTHER) {
                    throw new InvalidAssetException("newsletter hashtags must be <=5");
                }
            }
            case FAQ -> {
                if (outputContentProcessed.length() < FAQ_MIN_CHARS || outputContentProcessed.length() > FAQ_MAX_CHARS) {
                    throw new InvalidAssetException("faq outputContentProcessed must be 20..500 chars");
                }
                if (titlePost == null || titlePost.isBlank()) {
                    throw new InvalidAssetException("faq titlePost is required");
                }
                if (cta != null && !cta.isBlank()) {
                    throw new InvalidAssetException("faq cta must be empty");
                }
                if (hashtags.size() > MAX_TAGS_OTHER) {
                    throw new InvalidAssetException("faq hashtags must be <=5");
                }
            }
        }
    }

    private static int wordCount(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }
}
