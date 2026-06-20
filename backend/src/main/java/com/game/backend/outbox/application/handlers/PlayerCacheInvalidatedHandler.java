package com.game.backend.outbox.application.handlers;

import com.game.backend.cache.RedisCacheService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class PlayerCacheInvalidatedHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;
    private final RedisCacheService cacheService;

    public PlayerCacheInvalidatedHandler(
        OutboxPayloadParser payloadParser,
        RedisCacheService cacheService
    ) {
        this.payloadParser = payloadParser;
        this.cacheService = cacheService;
    }

    @Override
    public boolean supports(String eventType) {
        return "player_cache.invalidated".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = payloadParser.parseRequired(event);
        UUID playerId = payloadParser.playerIdRequired(event, payload);
        cacheService.evictPlayerAccess(playerId);
    }
}
