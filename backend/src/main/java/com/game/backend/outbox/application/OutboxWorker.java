package com.game.backend.outbox.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Polling worker для надежной доставки pending outbox events.
 */
@Service
@ConditionalOnProperty(prefix = "app.outbox", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxPublisher outboxPublisher;
    private final boolean workerEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryDelaySeconds;
    private final long processingTimeoutSeconds;

    public OutboxWorker(
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        OutboxPublisher outboxPublisher,
        @Value("${app.outbox.worker-enabled:true}") boolean workerEnabled,
        @Value("${app.outbox.batch-size:20}") int batchSize,
        @Value("${app.outbox.max-attempts:5}") int maxAttempts,
        @Value("${app.outbox.retry-delay-seconds:10}") long retryDelaySeconds,
        @Value("${app.outbox.processing-timeout-seconds:60}") long processingTimeoutSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.outboxPublisher = outboxPublisher;
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    /**
     * Периодически забирает пачку событий и доставляет их через OutboxPublisher.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (!workerEnabled) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        requeueTimedOutProcessingEvents(now);
        List<OutboxEvent> events = claimBatch(now);
        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private List<OutboxEvent> claimBatch(OffsetDateTime now) {
        return transactionTemplate.execute(status -> jdbcTemplate.query(
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
            now.plusSeconds(processingTimeoutSeconds)
        ));
    }

    private void publish(OutboxEvent event) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            outboxPublisher.publish(event);
            markProcessed(event.eventId(), now);
        } catch (RuntimeException exception) {
            markFailed(event, now, exception);
        }
    }

    private void markProcessed(UUID eventId, OffsetDateTime now) {
        jdbcTemplate.update(
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

    private void markFailed(OutboxEvent event, OffsetDateTime now, RuntimeException exception) {
        jdbcTemplate.update(
            """
                UPDATE outbox_events
                SET status = 'failed',
                    next_attempt_at = ?,
                    last_error = ?
                WHERE event_id = ?
                """,
            now.plusSeconds(retryDelaySeconds * Math.max(1, event.attempts())),
            exception.getMessage(),
            event.eventId()
        );
    }

    private void requeueTimedOutProcessingEvents(OffsetDateTime now) {
        jdbcTemplate.update(
            """
                UPDATE outbox_events
                SET status = 'failed',
                    last_error = 'processing timeout'
                WHERE status = 'processing'
                  AND next_attempt_at <= ?
                """,
            now
        );
    }
}
