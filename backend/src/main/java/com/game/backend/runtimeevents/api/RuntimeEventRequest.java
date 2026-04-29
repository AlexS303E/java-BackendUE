package com.game.backend.runtimeevents.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Факт матча, который Dedicated Server отправляет в backend во время runtime.
 */
public record RuntimeEventRequest(
    @NotNull
    UUID eventId,

    @NotNull
    Long eventSeq,

    @NotNull
    UUID matchId,

    @NotBlank
    String eventType,

    UUID playerId,

    @NotNull
    Integer payloadSchemaVersion,

    @NotNull
    OffsetDateTime occurredAt,

    @NotNull
    Map<String, Object> payload
) {
}
