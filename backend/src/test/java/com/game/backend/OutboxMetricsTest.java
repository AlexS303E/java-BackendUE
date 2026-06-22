package com.game.backend;

import com.game.backend.outbox.application.OutboxMetrics;
import com.game.backend.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void shouldExposeOutboxStatusAndLagGauges() {
        when(repository.countByStatus("pending")).thenReturn(2L);
        when(repository.countByStatus("failed")).thenReturn(1L);
        when(repository.countByStatus("dead_letter")).thenReturn(3L);
        when(repository.pendingLagSeconds(any())).thenReturn(42L);

        new OutboxMetrics(repository, meterRegistry);

        assertThat(meterRegistry.get("outbox.events").tag("status", "pending").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("outbox.events").tag("status", "failed").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.events").tag("status", "dead_letter").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("outbox.pending.lag.seconds").gauge().value()).isEqualTo(42.0);
    }

    @Test
    void shouldKeepMetricsScrapeSafeWhenRepositoryFails() {
        when(repository.countByStatus("pending")).thenThrow(new IllegalStateException("db down"));
        when(repository.pendingLagSeconds(any())).thenThrow(new IllegalStateException("db down"));

        new OutboxMetrics(repository, meterRegistry);

        assertThat(meterRegistry.get("outbox.events").tag("status", "pending").gauge().value()).isEqualTo(-1.0);
        assertThat(meterRegistry.get("outbox.pending.lag.seconds").gauge().value()).isEqualTo(-1.0);
    }
}
