package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Команда пересборки player_item_access projection из entitlement_ledger.
 */
public record AdminProjectionRebuildRequest(
    @NotNull
    UUID playerId,

    @NotBlank
    String reason
) {
}
