package com.game.backend.postmatch.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Решение игрока по pending change.
 */
public record PostMatchPendingChangeResolutionRequest(
    @NotBlank
    String resolution
) {
}
