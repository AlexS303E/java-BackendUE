package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Команда ручной инвалидации player-level cache/profile snapshots.
 */
public record AdminCacheInvalidateRequest(
    @NotNull
    UUID playerId,

    @NotBlank
    String reason
) {
}
