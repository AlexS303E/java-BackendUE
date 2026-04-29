package com.game.backend.presets.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Модуль, который клиент хочет поставить на mount при сохранении weapon preset.
 */
public record SaveModuleRequest(
    @NotBlank
    String mountId,

    @NotBlank
    String moduleId
) {
}
