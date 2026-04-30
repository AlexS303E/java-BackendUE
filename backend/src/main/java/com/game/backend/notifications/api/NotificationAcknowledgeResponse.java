package com.game.backend.notifications.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Результат подтверждения уведомления игроком.
 */
public record NotificationAcknowledgeResponse(
    UUID notificationId,
    String status,
    OffsetDateTime readAt
) {
}
