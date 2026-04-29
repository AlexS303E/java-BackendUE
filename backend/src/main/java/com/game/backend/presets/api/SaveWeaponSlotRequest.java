package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Содержимое одного weapon slot при полном сохранении weapon preset.
 */
public record SaveWeaponSlotRequest(
    @NotBlank
    String weaponSlotId,

    String weaponId,

    @NotNull
    List<@Valid SaveModuleRequest> modules
) {
}
