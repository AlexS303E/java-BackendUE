package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Содержимое одного weapon slot при полном сохранении weapon preset.
 */
public record SaveWeaponSlotRequest(
    @NotBlank
    String weaponSlotId,

    String weaponId,

    @NotNull
    @Size(max = 20)
    List<@Valid SaveModuleRequest> modules
) {
}
