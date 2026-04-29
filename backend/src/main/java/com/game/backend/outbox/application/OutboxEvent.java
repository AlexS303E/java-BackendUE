package com.game.backend.outbox.application;

import java.util.UUID;

/**
 * Событие, забранное worker-ом из outbox_events для доставки.
 */
public record OutboxEvent(
    UUID eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String payload,
    int payloadSchemaVersion,
    int attempts
) {
}
