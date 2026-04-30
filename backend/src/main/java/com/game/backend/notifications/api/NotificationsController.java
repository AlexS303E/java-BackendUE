package com.game.backend.notifications.api;

import com.game.backend.auth.application.CurrentPlayer;
import com.game.backend.notifications.application.PlayerNotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Player API для чтения уведомлений о важных backend-событиях.
 */
@RestController
public class NotificationsController {
    private final PlayerNotificationService playerNotificationService;

    public NotificationsController(PlayerNotificationService playerNotificationService) {
        this.playerNotificationService = playerNotificationService;
    }

    /**
     * Возвращает уведомления текущего игрока. По умолчанию клиент получает только unread.
     */
    @GetMapping("/me/notifications")
    NotificationsResponse getMyNotifications(
            Authentication authentication,
            @RequestParam(value = "status", defaultValue = "unread") String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        UUID playerId = CurrentPlayer.require(authentication).playerId();
        return playerNotificationService.getNotifications(playerId, status, limit);
    }

    /**
     * Помечает уведомление прочитанным; повторный вызов остается идемпотентным.
     */
    @PostMapping("/me/notifications/{notification_id}/read")
    NotificationAcknowledgeResponse markNotificationRead(
            Authentication authentication,
            @PathVariable("notification_id") UUID notificationId
    ) {
        UUID playerId = CurrentPlayer.require(authentication).playerId();
        return playerNotificationService.markRead(playerId, notificationId);
    }
}
