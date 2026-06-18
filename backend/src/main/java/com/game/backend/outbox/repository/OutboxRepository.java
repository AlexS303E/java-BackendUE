package com.game.backend.outbox.repository;

import com.game.backend.common.persistence.JdbcRepository;
import com.game.backend.outbox.application.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository extends JdbcRepository {
    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void insertPendingEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payloadJson,
        int payloadSchemaVersion,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO outbox_events(
                  event_id,
                  event_type,
                  aggregate_type,
                  aggregate_id,
                  payload,
                  payload_schema_version,
                  status,
                  attempts,
                  next_attempt_at,
                  created_at
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, 'pending', 0, ?, ?)
                """,
            eventId,
            eventType,
            aggregateType,
            aggregateId,
            payloadJson,
            payloadSchemaVersion,
            now,
            now
        );
    }

    public List<OutboxEvent> claimBatch(
        int maxAttempts,
        OffsetDateTime now,
        int batchSize,
        OffsetDateTime processingDeadline
    ) {
        return query(
            """
                WITH claimed AS (
                  SELECT event_id
                  FROM outbox_events
                  WHERE status IN ('pending', 'failed')
                    AND attempts < ?
                    AND next_attempt_at <= ?
                  ORDER BY created_at
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events oe
                SET status = 'processing',
                    attempts = oe.attempts + 1,
                    next_attempt_at = ?
                FROM claimed
                WHERE oe.event_id = claimed.event_id
                RETURNING
                  oe.event_id,
                  oe.event_type,
                  oe.aggregate_type,
                  oe.aggregate_id,
                  oe.payload::text AS payload,
                  oe.payload_schema_version,
                  oe.attempts
                """,
            (rs, rowNum) -> new OutboxEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("payload"),
                rs.getInt("payload_schema_version"),
                rs.getInt("attempts")
            ),
            maxAttempts,
            now,
            batchSize,
            processingDeadline
        );
    }

    public void markProcessed(UUID eventId, OffsetDateTime now) {
        update(
            """
                UPDATE outbox_events
                SET status = 'processed',
                    processed_at = ?,
                    last_error = null
                WHERE event_id = ?
                """,
            now,
            eventId
        );
    }

    public void markDeadLetter(UUID eventId, String error) {
        update(
            """
                UPDATE outbox_events
                SET status = 'dead_letter',
                    last_error = ?
                WHERE event_id = ?
                """,
            error,
            eventId
        );
    }

    public void markFailed(UUID eventId, OffsetDateTime nextAttemptAt, String error) {
        update(
            """
                UPDATE outbox_events
                SET status = 'failed',
                    next_attempt_at = ?,
                    last_error = ?
                WHERE event_id = ?
                """,
            nextAttemptAt,
            error,
            eventId
        );
    }

    public void requeueTimedOutProcessingEvents(int maxAttempts, OffsetDateTime now) {
        update(
            """
                UPDATE outbox_events
                SET status = CASE
                      WHEN attempts >= ? THEN 'dead_letter'
                      ELSE 'failed'
                    END,
                    last_error = CASE
                      WHEN attempts >= ? THEN 'processing timeout; moved to dead_letter'
                      ELSE 'processing timeout'
                    END
                WHERE status = 'processing'
                  AND next_attempt_at <= ?
                """,
            maxAttempts,
            maxAttempts,
            now
        );
    }
}
