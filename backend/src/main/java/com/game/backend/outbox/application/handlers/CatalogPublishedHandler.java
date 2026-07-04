package com.game.backend.outbox.application.handlers;

import com.game.backend.cache.RedisCacheService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventHandler;
import com.game.backend.outbox.application.OutboxPayloadParser;
import org.springframework.stereotype.Component;

@Component
public class CatalogPublishedHandler implements OutboxEventHandler {
    private final OutboxPayloadParser payloadParser;
    private final RedisCacheService cacheService;

    public CatalogPublishedHandler(
        OutboxPayloadParser payloadParser,
        RedisCacheService cacheService
    ) {
        this.payloadParser = payloadParser;
        this.cacheService = cacheService;
    }

    @Override
    public boolean supports(String eventType) {
        return "catalog.publish".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        String realmId = payloadParser.stringRequired(event, "realm_id");
        cacheService.evictCatalogSnapshots(realmId);
        cacheService.evictCatalogAllowsNewMatches(realmId);
    }
}
