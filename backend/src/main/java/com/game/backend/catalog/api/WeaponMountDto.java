package com.game.backend.catalog.api;

/**
 * Описание точки крепления модуля на оружии.
 */
public record WeaponMountDto(
    String mountId,
    long catalogVersion,
    String weaponId,
    String mountType,
    int mountIndex,
    boolean required,
    int displayOrder
) {
}
