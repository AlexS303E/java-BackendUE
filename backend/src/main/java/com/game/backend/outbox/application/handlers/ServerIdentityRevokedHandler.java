package com.game.backend.outbox.application.handlers;

import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

@Component
public class ServerIdentityRevokedHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;

    public ServerIdentityRevokedHandler(OutboxPayloadParser payloadParser) {
        this.payloadParser = payloadParser;
    }

    @Override
    public boolean supports(String eventType) {
        return "server_identity.revoked".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        payloadParser.validateRequired(event);
    }
}
