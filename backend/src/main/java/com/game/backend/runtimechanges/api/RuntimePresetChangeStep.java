package com.game.backend.runtimechanges.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Одно атомарное изменение: set/clear weapon или set/clear module.
 */
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
