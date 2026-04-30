package com.game.backend.notifications.api;

import java.util.List;
import java.util.UUID;

/**
 * Страница уведомлений игрока с примененным фильтром и лимитом.
 */
public record NotificationsResponse(
    UUID playerId,
    String status,
    int limit,
    List<NotificationDto> notifications
) {
}
