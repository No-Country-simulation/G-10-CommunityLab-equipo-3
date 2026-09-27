package com.nocountry.simulation.communitylab.domain.policy;

import com.nocountry.simulation.communitylab.domain.enums.MessageType;
import com.nocountry.simulation.communitylab.domain.enums.ai.Sentiment;

// This class is for scoring relevance of messages based on their type, sentiment, length, and other factors.
public final class RelevancePolicy {

    public static final int MIN_RELEVANT_LENGTH = 15;
    public static final int LOGRO_FLOOR = 70;
    public static final int DUDA_CAP = 69;

    private RelevancePolicy() {
    }

    /**
     * @param truncated accepted explicitly: a cut made by ingest (001) never
     *                  penalizes relevance (spec RF-01), so it is read and ignored by design.
     */
    public static int score(MessageType messageType, Sentiment sentiment, int contentLength, boolean truncated, int relevance) {
        if (contentLength < MIN_RELEVANT_LENGTH) {
            return 0;
        }
        int base = Math.min(100, Math.max(0, relevance));
        MessageType type = messageType == null ? MessageType.OTRO : messageType;
        return switch (type) {
            case LOGRO, TESTIMONIO -> sentiment == Sentiment.POSITIVO ? Math.max(base, LOGRO_FLOOR) : base;
            case DUDA -> Math.min(base, DUDA_CAP);
            default -> base;
        };
    }
}
