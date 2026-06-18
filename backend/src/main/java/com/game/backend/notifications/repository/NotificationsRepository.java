package com.game.backend.notifications.repository;

import com.game.backend.common.persistence.JdbcRepository;
import com.game.backend.notifications.api.NotificationAcknowledgeResponse;
import com.game.backend.notifications.api.NotificationDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class NotificationsRepository extends JdbcRepository {
    public NotificationsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void insertNotification(
        UUID notificationId,
        UUID playerId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payloadJson,
        int payloadSchemaVersion,
        OffsetDateTime now
    ) {
        update(
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
            payloadJson,
            payloadSchemaVersion,
            now
        );
    }

    public List<NotificationDto> findNotifications(
        UUID playerId,
        String status,
        int limit,
        Function<String, Map<String, Object>> payloadParser
    ) {
        if ("all".equals(status)) {
            return query(
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
                (rs, rowNum) -> new NotificationDto(
                    rs.getObject("notification_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getInt("payload_schema_version"),
                    payloadParser.apply(rs.getString("payload")),
                    rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("read_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class)
                ),
                playerId,
                limit
            );
        }

        return query(
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
            (rs, rowNum) -> new NotificationDto(
                rs.getObject("notification_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getInt("payload_schema_version"),
                payloadParser.apply(rs.getString("payload")),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("read_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            playerId,
            status,
            limit
        );
    }

    public void markRead(UUID playerId, UUID notificationId, OffsetDateTime now) {
        update(
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
    }

    public NotificationAcknowledgeResponse findAcknowledgeResponse(UUID playerId, UUID notificationId) {
        return query(
            """
                SELECT notification_id, status, read_at
                FROM player_notifications
                WHERE notification_id = ?
                  AND player_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return null;
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
}
