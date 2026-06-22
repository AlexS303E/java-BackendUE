package com.game.backend.outbox.application;

import com.game.backend.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class OutboxMetrics {
    private static final double ERROR_VALUE = -1.0;
    private static final List<String> STATUSES = List.of(
        "pending",
        "failed",
        "processing",
        "processed",
        "dead_letter"
    );

    private final OutboxRepository repository;

    public OutboxMetrics(OutboxRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        for (String status : STATUSES) {
            Gauge.builder("outbox.events", this, metrics -> metrics.count(status))
                .description("Outbox events by status")
                .tag("status", status)
                .register(meterRegistry);
        }
        Gauge.builder("outbox.pending.lag.seconds", this, OutboxMetrics::pendingLagSeconds)
            .description("Age in seconds of the oldest deliverable pending or failed outbox event")
            .register(meterRegistry);
    }

    double count(String status) {
        try {
            return repository.countByStatus(status);
        } catch (RuntimeException exception) {
            return ERROR_VALUE;
        }
    }

    double pendingLagSeconds() {
        try {
            return repository.pendingLagSeconds(OffsetDateTime.now());
        } catch (RuntimeException exception) {
            return ERROR_VALUE;
        }
    }
}
