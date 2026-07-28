package com.game.backend.outbox.application;

import com.game.backend.outbox.repository.OutboxRepository;
import com.game.backend.outbox.repository.OutboxRepository.OutboxEventRecord;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final OutboxRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final OutboxPublisher outboxPublisher;
    private final boolean workerEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryDelaySeconds;
    private final long processingTimeoutSeconds;
    private final int circuitBreakerFailureThreshold;
    private final long circuitBreakerCooldownSeconds;
    private final Counter circuitBreakerOpenedCounter;
    private final Counter lostLeaseCounter;
    private final String workerId = UUID.randomUUID().toString();

    private int consecutiveFailedBatches = 0;
    private OffsetDateTime circuitBreakerOpenUntil;

    public OutboxWorker(
        OutboxRepository repository,
        TransactionTemplate transactionTemplate,
        OutboxPublisher outboxPublisher,
        @Value("${app.outbox.worker-enabled:true}") boolean workerEnabled,
        @Value("${app.outbox.batch-size:20}") int batchSize,
        @Value("${app.outbox.max-attempts:5}") int maxAttempts,
        @Value("${app.outbox.retry-delay-seconds:10}") long retryDelaySeconds,
        @Value("${app.outbox.processing-timeout-seconds:60}") long processingTimeoutSeconds,
        @Value("${app.outbox.circuit-breaker.failure-threshold:3}") int circuitBreakerFailureThreshold,
        @Value("${app.outbox.circuit-breaker.cooldown-seconds:30}") long circuitBreakerCooldownSeconds,
        MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.outboxPublisher = outboxPublisher;
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
        this.circuitBreakerFailureThreshold = Math.max(1, circuitBreakerFailureThreshold);
        this.circuitBreakerCooldownSeconds = Math.max(1, circuitBreakerCooldownSeconds);
        this.circuitBreakerOpenedCounter = Counter.builder("outbox.circuit_breaker.opened")
            .description("Number of times the outbox worker circuit breaker opened")
            .register(meterRegistry);
        this.lostLeaseCounter = Counter.builder("outbox.lease.lost")
            .description("Outbox state transitions rejected because the worker lost its lease")
            .register(meterRegistry);
        Gauge.builder("outbox.circuit_breaker.open", this, OutboxWorker::circuitBreakerOpenValue)
            .description("Whether the outbox worker circuit breaker is currently open")
            .register(meterRegistry);
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
        if (isCircuitBreakerOpen(now)) {
            return;
        }

        requeueTimedOutProcessingEvents(now);
        List<OutboxEvent> events = claimBatch(now);
        if (events.isEmpty()) {
            consecutiveFailedBatches = 0;
            return;
        }

        int failures = 0;
        for (OutboxEvent event : events) {
            if (!publish(event)) {
                failures++;
            }
        }
        if (failures == events.size()) {
            recordFailedBatch(now);
        } else {
            consecutiveFailedBatches = 0;
        }
    }

    private List<OutboxEvent> claimBatch(OffsetDateTime now) {
        UUID processingToken = UUID.randomUUID();
        return transactionTemplate.execute(status -> repository.claimBatch(
            maxAttempts,
            now,
            batchSize,
            now.plusSeconds(processingTimeoutSeconds),
            workerId,
            processingToken
        )).stream()
            .map(this::toOutboxEvent)
            .toList();
    }

    private OutboxEvent toOutboxEvent(OutboxEventRecord record) {
        return new OutboxEvent(
            record.eventId(),
            record.eventType(),
            record.aggregateType(),
            record.aggregateId(),
            record.payload(),
            record.payloadSchemaVersion(),
            record.attempts(),
            record.processingToken()
        );
    }

    private boolean publish(OutboxEvent event) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            outboxPublisher.publish(event);
            return markProcessed(event, now);
        } catch (RuntimeException exception) {
            markFailed(event, now, exception);
            return false;
        }
    }

    private boolean markProcessed(OutboxEvent event, OffsetDateTime now) {
        return recordLeaseResult(repository.markProcessed(event.eventId(), event.processingToken(), now));
    }

    private void markFailed(OutboxEvent event, OffsetDateTime now, RuntimeException exception) {
        int newAttempts = event.attempts();
        if (newAttempts >= maxAttempts) {
            recordLeaseResult(repository.markDeadLetter(event.eventId(), event.processingToken(), exception.getMessage()));
        } else {
            recordLeaseResult(repository.markFailed(
                event.eventId(),
                event.processingToken(),
                now.plusSeconds(retryDelaySeconds * Math.max(1, newAttempts)),
                exception.getMessage()
            ));
        }
    }

    private boolean recordLeaseResult(int updatedRows) {
        if (updatedRows == 1) return true;
        lostLeaseCounter.increment();
        return false;
    }

    private void requeueTimedOutProcessingEvents(OffsetDateTime now) {
        repository.requeueTimedOutProcessingEvents(maxAttempts, now);
    }

    private boolean isCircuitBreakerOpen(OffsetDateTime now) {
        return circuitBreakerOpenUntil != null && circuitBreakerOpenUntil.isAfter(now);
    }

    private void recordFailedBatch(OffsetDateTime now) {
        consecutiveFailedBatches++;
        if (consecutiveFailedBatches >= circuitBreakerFailureThreshold) {
            circuitBreakerOpenUntil = now.plusSeconds(circuitBreakerCooldownSeconds);
            consecutiveFailedBatches = 0;
            circuitBreakerOpenedCounter.increment();
        }
    }

    double circuitBreakerOpenValue() {
        return isCircuitBreakerOpen(OffsetDateTime.now()) ? 1.0 : 0.0;
    }
}
