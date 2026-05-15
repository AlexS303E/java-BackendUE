package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Запрос полного сохранения weapon preset для указанной версии каталога.
 */
public record WeaponPresetSaveRequest(
    @NotNull
    @Min(1)
    Long catalogVersion,

    @NotEmpty
    @Size(max = 20)
    List<@Valid SaveWeaponSlotRequest> slots
) {
    @AssertTrue(message = "slots must not contain duplicate weapon_slot_id")
    public boolean isWeaponSlotIdsUnique() {
        if (slots == null) {
            return true;
        }
        Set<String> slotIds = new HashSet<>();
        for (SaveWeaponSlotRequest slot : slots) {
            if (slot != null && slot.weaponSlotId() != null && !slotIds.add(slot.weaponSlotId())) {
                return false;
            }
        }
        return true;
    }
}
