package com.game.backend.presets.api;

/**
 * Выбранный модуль на конкретном mount оружия.
 */
public record ModuleSelectionDto(
    String mountId,
    String moduleId
) {
}
