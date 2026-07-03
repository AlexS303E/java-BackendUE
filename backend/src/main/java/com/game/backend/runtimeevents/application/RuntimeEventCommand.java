package com.game.backend.runtimeevents.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime event command accepted from a dedicated server.
 */
public record RuntimeEventCommand(
    UUID eventId,
    Long eventSeq,
    UUID matchId,
    String eventType,
    UUID playerId,
    Integer payloadSchemaVersion,
    OffsetDateTime occurredAt,
    Map<String, Object> payload
) {
}
