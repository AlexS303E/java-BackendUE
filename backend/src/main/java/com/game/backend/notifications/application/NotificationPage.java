package com.game.backend.notifications.application;

import java.util.List;
import java.util.UUID;

/**
 * Page of player notifications with the applied filter.
 */
public record NotificationPage(
    UUID playerId,
    String status,
    int limit,
    List<NotificationEntry> notifications
) {
}
