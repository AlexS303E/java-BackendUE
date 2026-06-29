package com.game.backend.notifications.application;

import com.game.backend.notifications.repository.NotificationsRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.api.NotificationAcknowledgeResponse;
import com.game.backend.notifications.api.NotificationDto;
import com.game.backend.notifications.api.NotificationsResponse;
import com.game.backend.notifications.repository.NotificationsRepository.AcknowledgeRecord;
import com.game.backend.notifications.repository.NotificationsRepository.NotificationRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
        repository.insertNotification(
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
        return new NotificationsResponse(
            playerId,
            normalizedStatus,
            normalizedLimit,
            toNotificationDtos(repository.findNotifications(playerId, normalizedStatus, normalizedLimit))
        );
    }

    /**
     * Ставит status=read только для уведомления текущего игрока.
     */
    @Transactional
    public NotificationAcknowledgeResponse markRead(UUID playerId, UUID notificationId) {
        OffsetDateTime now = OffsetDateTime.now();
        repository.markRead(playerId, notificationId, now);

        return readAcknowledgeResponse(playerId, notificationId);
    }

    private NotificationAcknowledgeResponse readAcknowledgeResponse(UUID playerId, UUID notificationId) {
        AcknowledgeRecord record = repository.findAcknowledgeResponse(playerId, notificationId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification was not found");
        }
        return new NotificationAcknowledgeResponse(record.notificationId(), record.status(), record.readAt());
    }

    private List<NotificationDto> toNotificationDtos(List<NotificationRecord> records) {
        return records.stream()
            .map(record -> new NotificationDto(
                record.notificationId(),
                record.eventType(),
                record.aggregateType(),
                record.aggregateId(),
                record.payloadSchemaVersion(),
                parsePayload(record.payloadJson()),
                record.status(),
                record.createdAt(),
                record.readAt(),
                record.expiresAt()
            ))
            .toList();
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
