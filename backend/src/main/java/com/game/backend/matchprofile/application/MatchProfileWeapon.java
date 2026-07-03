package com.game.backend.matchprofile.application;

import java.util.List;

/**
 * Weapon and modules selected for one match profile weapon slot.
 */
public record MatchProfileWeapon(
    String weaponSlotId,
    String weaponId,
    List<MatchProfileModule> modules
) {
}
