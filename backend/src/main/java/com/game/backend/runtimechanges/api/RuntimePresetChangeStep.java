package com.game.backend.runtimechanges.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Одно атомарное изменение: set/clear weapon или set/clear module.
 */
public record RuntimePresetChangeStep(
    @NotBlank
    @Pattern(regexp = "set_weapon|clear_weapon|set_module|clear_module")
    String op,

    @NotBlank
    String weaponSlotId,

    String weaponId,

    String mountId,

    String moduleId
) {
}
