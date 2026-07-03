package com.game.backend.notifications.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Player-facing notification entry.
 */
public record NotificationEntry(
    UUID notificationId,
    String eventType,
    String aggregateType,
    String aggregateId,
    int payloadSchemaVersion,
    Map<String, Object> payload,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime readAt,
    OffsetDateTime expiresAt
) {
}
