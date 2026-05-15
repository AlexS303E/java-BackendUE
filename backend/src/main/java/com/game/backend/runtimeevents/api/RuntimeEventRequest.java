package com.game.backend.runtimeevents.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
    @Min(1)
    Long eventSeq,

    @NotNull
    UUID matchId,

    @NotBlank
    @Pattern(regexp = "player_spawned|loadout_applied|item_used|player_died|match_finished")
    String eventType,

    UUID playerId,

    @NotNull
    @Min(1)
    @Max(1)
    Integer payloadSchemaVersion,

    @NotNull
    OffsetDateTime occurredAt,

    @NotEmpty
    Map<String, Object> payload
) {
}
