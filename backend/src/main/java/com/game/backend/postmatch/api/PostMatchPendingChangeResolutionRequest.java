package com.game.backend.postmatch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Решение игрока по pending change.
 */
public record PostMatchPendingChangeResolutionRequest(
    @NotBlank
    @Pattern(regexp = "apply_if_still_valid|discard|manual_merge")
    String resolution
) {
}
