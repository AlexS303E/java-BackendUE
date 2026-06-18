package com.game.backend.outbox.application;

import com.game.backend.outbox.repository.OutboxRepository;

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

    public OutboxWorker(
        OutboxRepository repository,
        TransactionTemplate transactionTemplate,
        OutboxPublisher outboxPublisher,
        @Value("${app.outbox.worker-enabled:true}") boolean workerEnabled,
        @Value("${app.outbox.batch-size:20}") int batchSize,
        @Value("${app.outbox.max-attempts:5}") int maxAttempts,
        @Value("${app.outbox.retry-delay-seconds:10}") long retryDelaySeconds,
        @Value("${app.outbox.processing-timeout-seconds:60}") long processingTimeoutSeconds
    ) {
        this.repository = repository;
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
        return transactionTemplate.execute(status -> repository.claimBatch(
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
        repository.markProcessed(eventId, now);
    }

    private void markFailed(OutboxEvent event, OffsetDateTime now, RuntimeException exception) {
        int newAttempts = event.attempts();
        if (newAttempts >= maxAttempts) {
            repository.markDeadLetter(event.eventId(), exception.getMessage());
        } else {
            repository.markFailed(
                event.eventId(),
                now.plusSeconds(retryDelaySeconds * Math.max(1, newAttempts)),
                exception.getMessage()
            );
        }
    }

    private void requeueTimedOutProcessingEvents(OffsetDateTime now) {
        repository.requeueTimedOutProcessingEvents(maxAttempts, now);
    }
}
