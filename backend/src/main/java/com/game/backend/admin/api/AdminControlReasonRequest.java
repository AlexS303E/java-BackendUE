package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Minimal reason/comment body for legacy admin control write-actions.
 */
public record AdminControlReasonRequest(
    @NotBlank
    String reason,

    String comment
) {
}
