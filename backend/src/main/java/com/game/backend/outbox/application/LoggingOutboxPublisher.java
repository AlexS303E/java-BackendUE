package com.game.backend.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MVP publisher: фиксирует доставку в логах, позже заменяется на Kafka/Rabbit/HTTP publisher.
 * Заменен на {@link RoutingOutboxPublisher} — оставлен для reference и fallback-тестов.
 */
public class LoggingOutboxPublisher implements OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info(
            "Outbox event delivered event_id={} event_type={} aggregate_type={} aggregate_id={}",
            event.eventId(),
            event.eventType(),
            event.aggregateType(),
            event.aggregateId()
        );
    }
}
