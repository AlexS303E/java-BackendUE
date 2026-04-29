package com.game.backend.presets.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record WeaponPresetSaveRequest(
    @NotNull
    Long catalogVersion,

    @NotEmpty
    List<@Valid SaveWeaponSlotRequest> slots
) {
}
