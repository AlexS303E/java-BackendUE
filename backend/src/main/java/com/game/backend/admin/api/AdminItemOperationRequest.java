package com.game.backend.admin.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Машиночитаемая команда для явных admin item endpoints из ТЗ.
 */
public record AdminItemOperationRequest(
    @NotNull
    UUID playerId,

    @NotBlank
    String itemId,

    @NotNull
    @Min(1)
    Long catalogVersion,

    @NotBlank
    String reason,

    String disabledReason,

    String unlockHintCode,

    Map<String, Object> unlockHintPayload
) {
}
