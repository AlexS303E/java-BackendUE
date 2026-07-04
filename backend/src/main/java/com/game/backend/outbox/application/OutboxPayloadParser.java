package com.game.backend.outbox.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class OutboxPayloadParser {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public OutboxPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validateRequired(OutboxEvent event) {
        parseRequired(event);
    }

    public UUID playerIdRequired(OutboxEvent event) {
        return playerIdRequired(event, parseRequired(event));
    }

    public String stringRequired(OutboxEvent event, String fieldName) {
        return stringRequired(event, parseRequired(event), fieldName);
    }

    private Map<String, Object> parseRequired(OutboxEvent event) {
        String payload = event.payload();
        if (payload == null || payload.isBlank()) {
            throw new RuntimeException("Missing payload for event " + event.eventId());
        }
        try {
            return objectMapper.readValue(payload, JSON_MAP);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to parse payload for event " + event.eventId(), exception);
        }
    }

    private UUID playerIdRequired(OutboxEvent event, Map<String, Object> payload) {
        Object raw = payload.get("player_id");
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new RuntimeException("Invalid player_id in event " + event.eventId(), exception);
            }
        }
        throw new RuntimeException("Missing player_id in event " + event.eventId());
    }

    private String stringRequired(OutboxEvent event, Map<String, Object> payload, String fieldName) {
        Object raw = payload.get(fieldName);
        if (raw instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new RuntimeException("Missing " + fieldName + " in event " + event.eventId());
    }
}
