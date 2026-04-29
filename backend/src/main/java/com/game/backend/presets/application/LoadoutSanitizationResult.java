package com.game.backend.presets.application;

/**
 * Сводка того, сколько сохраненных loadout presets пришлось очистить после изменения access/catalog.
 */
public record LoadoutSanitizationResult(
    int sanitizedWeaponPresets,
    int sanitizedOutfitPresets
) {
    public static LoadoutSanitizationResult empty() {
        return new LoadoutSanitizationResult(0, 0);
    }
}
