package com.game.backend.outbox.application.handlers;

import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class OperationalEventRecordedHandler implements OutboxEventHandler {
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "server_runtime_event.recorded",
        "post_match_pending_change.created",
        "post_match_pending_change.resolved"
    );

    private final OutboxPayloadParser payloadParser;

    public OperationalEventRecordedHandler(OutboxPayloadParser payloadParser) {
        this.payloadParser = payloadParser;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        payloadParser.validateRequired(event);
    }
}
