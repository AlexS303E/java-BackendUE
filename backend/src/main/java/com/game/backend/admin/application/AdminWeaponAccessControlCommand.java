package com.game.backend.admin.application;

/**
 * Dashboard command for changing player weapon access through action scenarios.
 */
public record AdminWeaponAccessControlCommand(
    String weaponId,
    Long catalogVersion,
    String action,
    String reason,
    String comment
) {
}
