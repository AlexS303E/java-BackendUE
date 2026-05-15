package com.game.backend.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RoutingOutboxPublisher implements OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(RoutingOutboxPublisher.class);

    private final OutboxEventRouter router;

    public RoutingOutboxPublisher(OutboxEventRouter router) {
        this.router = router;
    }

    @Override
    public void publish(OutboxEvent event) {
        log.info("Outbox event delivered event_id={} event_type={} aggregate_type={} aggregate_id={}",
            event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId());
        router.route(event);
    }
}
