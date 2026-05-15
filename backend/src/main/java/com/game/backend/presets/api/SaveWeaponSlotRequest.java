package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @AssertTrue(message = "modules must not contain duplicate mount_id")
    public boolean isModuleMountIdsUnique() {
        if (modules == null) {
            return true;
        }
        Set<String> mountIds = new HashSet<>();
        for (SaveModuleRequest module : modules) {
            if (module != null && module.mountId() != null && !mountIds.add(module.mountId())) {
                return false;
            }
        }
        return true;
    }
}
