package com.game.backend.notifications.application;

import com.game.backend.notifications.repository.NotificationsRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.api.NotificationAcknowledgeResponse;
import com.game.backend.notifications.api.NotificationDto;
import com.game.backend.notifications.api.NotificationsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Хранит player-facing уведомления, которые клиент UE может использовать как ленту изменений.
 */
@Service
public class PlayerNotificationService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final int MAX_LIMIT = 100;
    private static final Set<String> READABLE_STATUSES = Set.of("unread", "read", "archived", "all");

    private final NotificationsRepository repository;
    private final ObjectMapper objectMapper;

    public PlayerNotificationService(NotificationsRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Создает unread-уведомление внутри текущей доменной транзакции.
     */
    public UUID record(
        UUID playerId,
        String eventType,
        String aggregateType,
        String aggregateId,
        int payloadSchemaVersion,
        Map<String, Object> payload,
        OffsetDateTime now
    ) {
        UUID notificationId = UUID.randomUUID();
        repository.update(
            """
                INSERT INTO player_notifications(
                  notification_id,
                  player_id,
                  event_type,
                  aggregate_type,
                  aggregate_id,
                  payload,
                  payload_schema_version,
                  status,
                  created_at
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'unread', ?)
                """,
            notificationId,
            playerId,
            eventType,
            aggregateType,
            aggregateId,
            toJson(payload),
            payloadSchemaVersion,
            now
        );
        return notificationId;
    }

    /**
     * Читает последние уведомления игрока с фильтром по статусу.
     */
    @Transactional(readOnly = true)
    public NotificationsResponse getNotifications(UUID playerId, String status, int limit) {
        String normalizedStatus = normalizeStatus(status);
        int normalizedLimit = normalizeLimit(limit);
        if ("all".equals(normalizedStatus)) {
            return new NotificationsResponse(
                playerId,
                normalizedStatus,
                normalizedLimit,
                repository.query(
                    """
                        SELECT
                          notification_id,
                          event_type,
                          aggregate_type,
                          aggregate_id,
                          payload_schema_version,
                          payload::text AS payload,
                          status,
                          created_at,
                          read_at,
                          expires_at
                        FROM player_notifications
                        WHERE player_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                    (rs, rowNum) -> notificationDto(
                        rs.getObject("notification_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getInt("payload_schema_version"),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("read_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)
                    ),
                    playerId,
                    normalizedLimit
                )
            );
        }

        return new NotificationsResponse(
            playerId,
            normalizedStatus,
            normalizedLimit,
            repository.query(
                """
                    SELECT
                      notification_id,
                      event_type,
                      aggregate_type,
                      aggregate_id,
                      payload_schema_version,
                      payload::text AS payload,
                      status,
                      created_at,
                      read_at,
                      expires_at
                    FROM player_notifications
                    WHERE player_id = ?
                      AND status = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """,
                (rs, rowNum) -> notificationDto(
                    rs.getObject("notification_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getInt("payload_schema_version"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("read_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class)
                ),
                playerId,
                normalizedStatus,
                normalizedLimit
            )
        );
    }

    /**
     * Ставит status=read только для уведомления текущего игрока.
     */
    @Transactional
    public NotificationAcknowledgeResponse markRead(UUID playerId, UUID notificationId) {
        OffsetDateTime now = OffsetDateTime.now();
        repository.update(
            """
                UPDATE player_notifications
                SET status = 'read',
                    read_at = COALESCE(read_at, ?)
                WHERE notification_id = ?
                  AND player_id = ?
                  AND status = 'unread'
                """,
            now,
            notificationId,
            playerId
        );

        return readAcknowledgeResponse(playerId, notificationId);
    }

    private NotificationAcknowledgeResponse readAcknowledgeResponse(UUID playerId, UUID notificationId) {
        return repository.query(
            """
                SELECT notification_id, status, read_at
                FROM player_notifications
                WHERE notification_id = ?
                  AND player_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification was not found");
                }
                return new NotificationAcknowledgeResponse(
                    rs.getObject("notification_id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("read_at", OffsetDateTime.class)
                );
            },
            notificationId,
            playerId
        );
    }

    private NotificationDto notificationDto(
        UUID notificationId,
        String eventType,
        String aggregateType,
        String aggregateId,
        int payloadSchemaVersion,
        String payload,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime readAt,
        OffsetDateTime expiresAt
    ) {
        return new NotificationDto(
            notificationId,
            eventType,
            aggregateType,
            aggregateId,
            payloadSchemaVersion,
            parsePayload(payload),
            status,
            createdAt,
            readAt,
            expiresAt
        );
    }

    private String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank() ? "unread" : status.toLowerCase(Locale.ROOT);
        if (!READABLE_STATUSES.contains(normalized)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported notification status: " + status
            );
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Notification limit must be between 1 and " + MAX_LIMIT
            );
        }
        return limit;
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "NOTIFICATION_PAYLOAD_PARSE_FAILED", "Unable to parse notification payload");
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "NOTIFICATION_PAYLOAD_SERIALIZATION_FAILED", "Unable to serialize notification payload");
        }
    }
}
