package com.game.backend.catalog.application;

/**
 * Weapon mount metadata for a published catalog version.
 */
public record WeaponMount(
    String mountId,
    long catalogVersion,
    String weaponId,
    String mountType,
    int mountIndex,
    boolean required,
    int displayOrder
) {
}
