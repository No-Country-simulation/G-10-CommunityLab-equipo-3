package com.nocountry.simulation.communitylab.application.event;

import com.nocountry.simulation.communitylab.application.dtos.ChannelMessage;

/**
 * Fired after a Discord message is normalized, so spec 002 can analyze it
 * in background without blocking the JDA gateway thread (plan 001 §1).
 * No listener exists yet in 001: publishing is a no-op until 002 adds
 * an {@code @Async @EventListener} consumer.
 */
public record IngestAcceptedEvent(ChannelMessage message) {
}
