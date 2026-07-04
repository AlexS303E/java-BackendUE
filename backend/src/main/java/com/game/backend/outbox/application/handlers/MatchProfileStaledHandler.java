package com.game.backend.outbox.application.handlers;

import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MatchProfileStaledHandler implements OutboxEventHandler {
    private static final Logger log = LoggerFactory.getLogger(MatchProfileStaledHandler.class);

    private final OutboxPayloadParser payloadParser;

    public MatchProfileStaledHandler(OutboxPayloadParser payloadParser) {
        this.payloadParser = payloadParser;
    }

    @Override
    public boolean supports(String eventType) {
        return "match_profile.staled".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            payloadParser.validateRequired(event);
        } catch (RuntimeException exception) {
            log.warn("Ignoring malformed notification-like outbox event event_id={} event_type={}: {}",
                event.eventId(), event.eventType(), exception.getMessage());
        }
    }
}
