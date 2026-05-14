package com.game.backend;

import com.game.backend.outbox.application.OutboxWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@ActiveProfiles("local")
@Transactional
class OutboxWorkerIntegrationTest {
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID eventId;

    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE event_id = ?", eventId);
        }
    }

    @Test
    void shouldMoveTimedOutProcessingEventWithExhaustedAttemptsToDeadLetter() {
        eventId = UUID.randomUUID();
        OffsetDateTime timedOutAt = OffsetDateTime.now().minusSeconds(5);
        jdbcTemplate.update(
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
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """,
                eventId,
                "test.exhausted_processing_timeout",
                "test",
                "test-" + eventId,
                "{}",
                1,
                "processing",
                MAX_ATTEMPTS,
                timedOutAt,
                timedOutAt.minusMinutes(1)
        );

        AtomicBoolean publishedTargetEvent = new AtomicBoolean(false);
        OutboxWorker worker = new OutboxWorker(
                jdbcTemplate,
                transactionTemplate,
                event -> {
                    if (event.eventId().equals(eventId)) {
                        publishedTargetEvent.set(true);
                    }
                },
                true,
                20,
                MAX_ATTEMPTS,
                10,
                60
        );

        worker.poll();

        Map<String, Object> event = jdbcTemplate.queryForMap(
                """
                    SELECT status, last_error
                    FROM outbox_events
                    WHERE event_id = ?
                    """,
                eventId
        );
        assertThat(event.get("status")).isEqualTo("dead_letter");
        assertThat(event.get("last_error")).isEqualTo("processing timeout; moved to dead_letter");
        assertThat(publishedTargetEvent.get()).isFalse();
    }
}
