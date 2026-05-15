package com.game.backend.outbox.application.handlers;

import com.game.backend.cache.RedisCacheService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CatalogRolledBackHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;
    private final RedisCacheService cacheService;

    public CatalogRolledBackHandler(
        OutboxPayloadParser payloadParser,
        RedisCacheService cacheService
    ) {
        this.payloadParser = payloadParser;
        this.cacheService = cacheService;
    }

    @Override
    public boolean supports(String eventType) {
        return "catalog.rollback".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = payloadParser.parseRequired(event);
        String realmId = payloadParser.stringRequired(event, payload, "realm_id");
        cacheService.evictCatalogSnapshots(realmId);
        cacheService.evictCatalogAllowsNewMatches(realmId);
    }
}
