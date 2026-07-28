package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.outbox.application.OutboxEvent;
import com.game.backend.outbox.application.OutboxEventRouter;
import com.game.backend.outbox.application.OutboxPayloadParser;
import com.game.backend.outbox.application.handlers.CatalogPublishedHandler;
import com.game.backend.outbox.application.handlers.OperationalEventRecordedHandler;
import com.game.backend.outbox.application.handlers.PlayerAccessChangedHandler;
import com.game.backend.outbox.application.handlers.PlayerCacheInvalidatedHandler;
import com.game.backend.outbox.application.handlers.ServerIdentityRevokedHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventHandlersTest {
    private final OutboxPayloadParser parser = new OutboxPayloadParser(new ObjectMapper());

    @Test
    void playerAccessHandlerShouldEvictAccessCacheAndMarkProfilesStale() {
        UUID playerId = UUID.randomUUID();
        RedisCacheService cacheService = mock(RedisCacheService.class);
        MatchProfileInvalidationService invalidationService = mock(MatchProfileInvalidationService.class);
        when(invalidationService.invalidateForPlayer(any(), any(), any(), any())).thenReturn(0);
        PlayerAccessChangedHandler handler = new PlayerAccessChangedHandler(parser, cacheService, invalidationService);

        handler.handle(event("player_access.changed", "{\"player_id\":\"" + playerId + "\"}"));

        verify(cacheService).evictPlayerAccess(playerId);
        verify(invalidationService).invalidateForPlayer(eq(playerId), eq("access_changed"), any(UUID.class), any(OffsetDateTime.class));
    }

    @Test
    void catalogPublishedHandlerShouldEvictCatalogCaches() {
        RedisCacheService cacheService = mock(RedisCacheService.class);
        CatalogPublishedHandler handler = new CatalogPublishedHandler(parser, cacheService);

        handler.handle(event("catalog.publish", "{\"realm_id\":\"global\"}"));

        verify(cacheService).evictCatalogSnapshots("global");
        verify(cacheService).evictCatalogAllowsNewMatches("global");
    }

    @Test
    void playerCacheInvalidatedHandlerShouldEvictAccessCache() {
        UUID playerId = UUID.randomUUID();
        RedisCacheService cacheService = mock(RedisCacheService.class);
        PlayerCacheInvalidatedHandler handler = new PlayerCacheInvalidatedHandler(parser, cacheService);

        handler.handle(event("player_cache.invalidated", "{\"player_id\":\"" + playerId + "\"}"));

        verify(cacheService).evictPlayerAccess(playerId);
    }

    @Test
    void routerShouldRejectUnsupportedEventType() {
        OutboxEventRouter router = new OutboxEventRouter(List.of());

        assertThatThrownBy(() -> router.route(event("unknown.event", "{}")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unsupported outbox event_type=unknown.event");
    }

    @Test
    void operationalEventHandlerShouldAcceptKnownNotificationLikeEvents() {
        OperationalEventRecordedHandler handler = new OperationalEventRecordedHandler(parser);

        handler.handle(event("server_runtime_event.recorded", "{\"event_id\":\"" + UUID.randomUUID() + "\"}"));
        handler.handle(event("post_match_pending_change.created", "{\"pending_change_id\":\"" + UUID.randomUUID() + "\"}"));
        handler.handle(event("post_match_pending_change.resolved", "{\"pending_change_id\":\"" + UUID.randomUUID() + "\"}"));
    }

    @Test
    void serverIdentityRevokedHandlerShouldRejectMalformedPayload() {
        ServerIdentityRevokedHandler handler = new ServerIdentityRevokedHandler(parser);

        assertThatThrownBy(() -> handler.handle(event("server_identity.revoked", "")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Missing payload");
    }

    private OutboxEvent event(String eventType, String payload) {
        return new OutboxEvent(
                UUID.randomUUID(),
                eventType,
                "test",
                "test",
                payload,
                1,
                1,
                UUID.randomUUID()
        );
    }
}
