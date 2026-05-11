package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Запрос полного сохранения weapon preset для указанной версии каталога.
 */
public record WeaponPresetSaveRequest(
    @NotNull
    Long catalogVersion,

    @NotEmpty
    @Size(max = 20)
    List<@Valid SaveWeaponSlotRequest> slots
) {
}
