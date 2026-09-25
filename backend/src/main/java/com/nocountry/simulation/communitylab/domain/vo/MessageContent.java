package com.nocountry.simulation.communitylab.domain.vo;

import com.nocountry.simulation.communitylab.domain.exception.InvalidCommentException;

public record MessageContent(String value, boolean truncated) {

    public static final int MAX_LENGTH = 2000;

    public static MessageContent normalize(String message) {
        // Filter 1 -> Validate content of message
        if (message == null) {
            throw new InvalidCommentException("content is required");
        }

        // Formatter message
        String trimmed = message.trim();

        // Filter 2 -> Validate content of message if is empty
        if (trimmed.isEmpty()) {
            throw new InvalidCommentException("content must not be empty");
        }

        // Filter 3 -> Validate content of message if is longer than MAX_LENGTH
        if (trimmed.length() <= MAX_LENGTH) {
            return new MessageContent(trimmed, false);
        }
        int endIndex = MAX_LENGTH;

        // Don't split a UTF-16 for (emoji) at the cut point.
        if (Character.isHighSurrogate(trimmed.charAt(endIndex - 1))
                && Character.isLowSurrogate(trimmed.charAt(endIndex))) {
            endIndex--;
        }
        return new MessageContent(trimmed.substring(0, endIndex), true);
    }
}
