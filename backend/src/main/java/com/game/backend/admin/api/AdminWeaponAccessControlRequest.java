package com.game.backend.admin.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Команда dashboard-а на изменение доступа игрока к оружию через action-сценарий.
 */
public record AdminWeaponAccessControlRequest(
    @NotBlank
    String weaponId,

    @NotNull
    @Min(1)
    Long catalogVersion,

    @NotBlank
    String action,

    @NotBlank
    String reason,

    String comment
) {
}
