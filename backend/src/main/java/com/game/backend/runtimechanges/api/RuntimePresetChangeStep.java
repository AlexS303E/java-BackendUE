package com.game.backend.runtimechanges.api;

import jakarta.validation.constraints.NotBlank;

public record RuntimePresetChangeStep(
    @NotBlank
    String op,

    @NotBlank
    String weaponSlotId,

    String weaponId,

    String mountId,

    String moduleId
) {
}
