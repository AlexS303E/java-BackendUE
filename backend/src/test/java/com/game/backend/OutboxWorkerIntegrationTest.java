package com.game.backend;

import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.outbox.application.OutboxWorker;
import com.game.backend.outbox.application.RoutingOutboxPublisher;
import com.game.backend.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private final List<UUID> eventIds = new ArrayList<>();
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void retireExistingOutboxEvents() {
        meterRegistry = new SimpleMeterRegistry();
        jdbcTemplate.update("UPDATE outbox_events SET status = 'processed' WHERE status IN ('pending', 'failed')");
    }

    @AfterEach
    void cleanUp() {
        for (UUID eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE event_id = ?", eventId);
        }
    }

    @Test
    void shouldMoveTimedOutProcessingEventWithExhaustedAttemptsToDeadLetter() {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
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
                new OutboxRepository(jdbcTemplate),
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
                60,
                3,
                30,
                meterRegistry
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

    @Test
    void shouldRetryCriticalEventWithBadPayload() {
        UUID eventId = insertOutboxEvent("weapon_preset.saved", "{}");
        OutboxWorker worker = worker(new RoutingOutboxPublisher(realRouter()));

        worker.poll();

        Map<String, Object> event = event(eventId);
        assertThat(event.get("status")).isEqualTo("failed");
        assertThat(event.get("attempts")).isEqualTo(1);
        assertThat((String) event.get("last_error")).contains("Missing player_id");
    }

    @Test
    void shouldProcessMalformedNotificationLikeEventWithoutRetry() {
        UUID eventId = insertOutboxEvent("match_profile.staled", "{}");
        OutboxWorker worker = worker(new RoutingOutboxPublisher(realRouter()));

        worker.poll();

        Map<String, Object> event = event(eventId);
        assertThat(event.get("status")).isEqualTo("processed");
        assertThat(event.get("attempts")).isEqualTo(1);
        assertThat(event.get("last_error")).isNull();
    }

    @Test
    void shouldOpenCircuitBreakerAfterFailedBatchAndStopClaimingNewEvents() {
        UUID firstEventId = insertOutboxEvent("weapon_preset.saved", "{}");
        UUID secondEventId = insertOutboxEvent("weapon_preset.saved", "{}");
        AtomicInteger publishAttempts = new AtomicInteger();
        OutboxWorker worker = new OutboxWorker(
                new OutboxRepository(jdbcTemplate),
                transactionTemplate,
                event -> {
                    publishAttempts.incrementAndGet();
                    throw new RuntimeException("downstream unavailable");
                },
                true,
                1,
                MAX_ATTEMPTS,
                10,
                60,
                1,
                60,
                meterRegistry
        );

        worker.poll();
        worker.poll();

        Map<String, Object> firstEvent = event(firstEventId);
        Map<String, Object> secondEvent = event(secondEventId);
        assertThat(publishAttempts.get()).isEqualTo(1);
        assertThat(firstEvent.get("status")).isEqualTo("failed");
        assertThat(firstEvent.get("attempts")).isEqualTo(1);
        assertThat(secondEvent.get("status")).isEqualTo("pending");
        assertThat(secondEvent.get("attempts")).isEqualTo(0);
        assertThat(meterRegistry.get("outbox.circuit_breaker.opened").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.circuit_breaker.open").gauge().value()).isEqualTo(1.0);
    }

    private UUID insertOutboxEvent(String eventType, String payload) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        OffsetDateTime now = OffsetDateTime.now().minusSeconds(1);
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
                    VALUES (?, ?, ?, ?, ?::jsonb, 1, 'pending', 0, ?, ?)
                    """,
                eventId,
                eventType,
                "test",
                "test-" + eventId,
                payload,
                now,
                now
        );
        return eventId;
    }

    private Map<String, Object> event(UUID eventId) {
        return jdbcTemplate.queryForMap(
                """
                    SELECT status, attempts, last_error
                    FROM outbox_events
                    WHERE event_id = ?
                    """,
                eventId
        );
    }

    private OutboxWorker worker(com.game.backend.outbox.application.OutboxPublisher outboxPublisher) {
        return new OutboxWorker(
                new OutboxRepository(jdbcTemplate),
                transactionTemplate,
                outboxPublisher,
                true,
                20,
                MAX_ATTEMPTS,
                10,
                60,
                3,
                30,
                meterRegistry
        );
    }

    private com.game.backend.outbox.application.OutboxEventRouter realRouter() {
        MatchProfileInvalidationService invalidationService = mock(MatchProfileInvalidationService.class);
        when(invalidationService.invalidateForPlayer(any(), any(), any(), any())).thenReturn(0);
        return new com.game.backend.outbox.application.OutboxEventRouter(List.of(
                new com.game.backend.outbox.application.handlers.WeaponPresetChangedHandler(
                        new com.game.backend.outbox.application.OutboxPayloadParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                        invalidationService
                ),
                new com.game.backend.outbox.application.handlers.MatchProfileStaledHandler(
                        new com.game.backend.outbox.application.OutboxPayloadParser(new com.fasterxml.jackson.databind.ObjectMapper())
                )
        ));
    }
}
