package com.game.backend.presets.api;

import jakarta.validation.constraints.NotBlank;

public record SaveModuleRequest(
    @NotBlank
    String mountId,

    @NotBlank
    String moduleId
) {
}
