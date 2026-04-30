package com.game.backend.notifications.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Одно уведомление в player-facing ленте изменений.
 */
public record NotificationDto(
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
