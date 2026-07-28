package com.game.backend.outbox.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository extends JdbcRepository {
    public record OutboxEventRecord(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload,
        int payloadSchemaVersion,
        int attempts,
        UUID processingToken
    ) {
    }

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

    public List<OutboxEventRecord> claimBatch(
        int maxAttempts,
        OffsetDateTime now,
        int batchSize,
        OffsetDateTime processingDeadline,
        String processingOwner,
        UUID processingToken
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
                    next_attempt_at = ?,
                    processing_owner = ?,
                    processing_token = ?,
                    processing_started_at = ?,
                    processing_deadline = ?
                FROM claimed
                WHERE oe.event_id = claimed.event_id
                RETURNING
                  oe.event_id,
                  oe.event_type,
                  oe.aggregate_type,
                  oe.aggregate_id,
                  oe.payload::text AS payload,
                  oe.payload_schema_version,
                  oe.attempts,
                  oe.processing_token
                """,
            (rs, rowNum) -> new OutboxEventRecord(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("payload"),
                rs.getInt("payload_schema_version"),
                rs.getInt("attempts"),
                rs.getObject("processing_token", UUID.class)
            ),
            maxAttempts,
            now,
            batchSize,
            processingDeadline,
            processingOwner,
            processingToken,
            now,
            processingDeadline
        );
    }

    public int markProcessed(UUID eventId, UUID processingToken, OffsetDateTime now) {
        return update(
            """
                UPDATE outbox_events
                SET status = 'processed',
                    processed_at = ?,
                    last_error = null,
                    processing_owner = null,
                    processing_token = null,
                    processing_started_at = null,
                    processing_deadline = null
                WHERE event_id = ?
                  AND status = 'processing'
                  AND processing_token = ?
                """,
            now,
            eventId,
            processingToken
        );
    }

    public int markDeadLetter(UUID eventId, UUID processingToken, String error) {
        return update(
            """
                UPDATE outbox_events
                SET status = 'dead_letter',
                    last_error = ?,
                    processing_owner = null,
                    processing_token = null,
                    processing_started_at = null,
                    processing_deadline = null
                WHERE event_id = ?
                  AND status = 'processing'
                  AND processing_token = ?
                """,
            error,
            eventId,
            processingToken
        );
    }

    public int markFailed(UUID eventId, UUID processingToken, OffsetDateTime nextAttemptAt, String error) {
        return update(
            """
                UPDATE outbox_events
                SET status = 'failed',
                    next_attempt_at = ?,
                    last_error = ?,
                    processing_owner = null,
                    processing_token = null,
                    processing_started_at = null,
                    processing_deadline = null
                WHERE event_id = ?
                  AND status = 'processing'
                  AND processing_token = ?
                """,
            nextAttemptAt,
            error,
            eventId,
            processingToken
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
                    END,
                    processing_owner = null,
                    processing_token = null,
                    processing_started_at = null,
                    processing_deadline = null
                WHERE status = 'processing'
                  AND next_attempt_at <= ?
                """,
            maxAttempts,
            maxAttempts,
            now
        );
    }

    public long countByStatus(String status) {
        Long count = queryForObject(
            "SELECT count(*) FROM outbox_events WHERE status = ?",
            Long.class,
            status
        );
        return count == null ? 0 : count;
    }

    public long pendingLagSeconds(OffsetDateTime now) {
        Long lag = queryForObject(
            """
                SELECT COALESCE(EXTRACT(EPOCH FROM (? - MIN(created_at)))::bigint, 0)
                FROM outbox_events
                WHERE status IN ('pending', 'failed')
                  AND next_attempt_at <= ?
                """,
            Long.class,
            now,
            now
        );
        return lag == null ? 0 : Math.max(0, lag);
    }
}
