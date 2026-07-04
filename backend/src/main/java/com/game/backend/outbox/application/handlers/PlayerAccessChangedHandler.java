package com.game.backend.outbox.application.handlers;

import com.game.backend.cache.RedisCacheService;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class PlayerAccessChangedHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;
    private final RedisCacheService cacheService;
    private final MatchProfileInvalidationService invalidationService;

    public PlayerAccessChangedHandler(
        OutboxPayloadParser payloadParser,
        RedisCacheService cacheService,
        MatchProfileInvalidationService invalidationService
    ) {
        this.payloadParser = payloadParser;
        this.cacheService = cacheService;
        this.invalidationService = invalidationService;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("player_access.");
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID playerId = payloadParser.playerIdRequired(event);
        cacheService.evictPlayerAccess(playerId);
        invalidationService.invalidateForPlayer(playerId, "access_changed", event.eventId(), OffsetDateTime.now());
    }
}
