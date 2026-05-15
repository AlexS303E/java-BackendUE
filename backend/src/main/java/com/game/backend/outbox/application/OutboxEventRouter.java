package com.game.backend.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutboxEventRouter {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventRouter.class);

    private final List<OutboxEventHandler> handlers;

    public OutboxEventRouter(List<OutboxEventHandler> handlers) {
        this.handlers = handlers;
    }

    public void route(OutboxEvent event) {
        String eventType = event.eventType();
        if (eventType == null || eventType.isBlank()) {
            log.info("Outbox event has no event_type event_id={}", event.eventId());
            return;
        }

        for (OutboxEventHandler handler : handlers) {
            if (handler.supports(eventType)) {
                handler.handle(event);
                return;
            }
        }

        log.info("Outbox event has no handler event_id={} event_type={}", event.eventId(), eventType);
    }
}
