package com.game.backend.outbox.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class RoutingOutboxPublisher implements OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(RoutingOutboxPublisher.class);
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final RedisCacheService cacheService;

    public RoutingOutboxPublisher(ObjectMapper objectMapper, RedisCacheService cacheService) {
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    @Override
    public void publish(OutboxEvent event) {
        log.info("Outbox event delivered event_id={} event_type={} aggregate_type={} aggregate_id={}",
            event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId());

        Map<String, Object> payload = parsePayload(event.payload());
        if (payload == null) {
            return;
        }

        String eventType = event.eventType();
        if (eventType == null) {
            return;
        }

        try {
            if (eventType.startsWith("weapon_preset.") || eventType.startsWith("outfit_preset.")) {
                handlePresetEvent(payload);
            } else if (eventType.startsWith("player_access.")) {
                handleAccessEvent(payload);
            } else if ("match_profile.staled".equals(eventType)) {
                handleProfileStaledEvent(payload);
            }
        } catch (RuntimeException exception) {
            log.warn("Outbox routing failed for event_id={} event_type={}: {}",
                event.eventId(), event.eventType(), exception.getMessage());
        }
    }

    private void handlePresetEvent(Map<String, Object> payload) {
        UUID playerId = extractPlayerId(payload);
        if (playerId != null) {
            cacheService.evictPlayerAccess(playerId);
        }
    }

    private void handleAccessEvent(Map<String, Object> payload) {
        UUID playerId = extractPlayerId(payload);
        if (playerId != null) {
            cacheService.evictPlayerAccess(playerId);
        }
    }

    private void handleProfileStaledEvent(Map<String, Object> payload) {
        // match_profile.staled is an output notification; no downstream action needed in MVP
    }

    private UUID extractPlayerId(Map<String, Object> payload) {
        if (payload == null) return null;
        Object raw = payload.get("player_id");
        if (raw instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
        }
        if (raw instanceof UUID u) return u;
        return null;
    }

    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, JSON_MAP);
        } catch (Exception exception) {
            log.warn("Failed to parse outbox event payload: {}", exception.getMessage());
            return null;
        }
    }
}
