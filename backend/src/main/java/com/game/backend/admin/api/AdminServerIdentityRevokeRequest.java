package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Команда отзыва server identity через ТЗ-совместимый endpoint.
 */
public record AdminServerIdentityRevokeRequest(
    @NotNull
    UUID serverId,

    @NotBlank
    String reason
) {
}
