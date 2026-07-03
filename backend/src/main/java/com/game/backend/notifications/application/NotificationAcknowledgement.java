package com.game.backend.notifications.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of acknowledging a player notification.
 */
public record NotificationAcknowledgement(
    UUID notificationId,
    String status,
    OffsetDateTime readAt
) {
}
