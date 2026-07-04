package com.game.backend.outbox.application.handlers;

import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class WeaponPresetChangedHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;
    private final MatchProfileInvalidationService invalidationService;

    public WeaponPresetChangedHandler(
        OutboxPayloadParser payloadParser,
        MatchProfileInvalidationService invalidationService
    ) {
        this.payloadParser = payloadParser;
        this.invalidationService = invalidationService;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("weapon_preset.");
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID playerId = payloadParser.playerIdRequired(event);
        invalidationService.invalidateForPlayer(playerId, "preset_updated", event.eventId(), OffsetDateTime.now());
    }
}
