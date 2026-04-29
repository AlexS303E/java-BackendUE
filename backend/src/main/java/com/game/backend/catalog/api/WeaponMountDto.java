package com.game.backend.catalog.api;

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
