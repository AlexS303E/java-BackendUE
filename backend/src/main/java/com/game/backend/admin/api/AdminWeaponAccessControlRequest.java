package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Команда dashboard-а на изменение доступа игрока к оружию через action-сценарий.
 */
public record AdminWeaponAccessControlRequest(
    @NotBlank
    String weaponId,

    @NotNull
    Long catalogVersion,

    @NotBlank
    String action,

    @NotBlank
    String reason,

    String comment
) {
}
