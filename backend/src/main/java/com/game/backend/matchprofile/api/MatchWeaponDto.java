package com.game.backend.matchprofile.api;

import java.util.List;

/**
 * Оружие и модули, выбранные для одного weapon slot в match profile.
 */
public record MatchWeaponDto(
    String weaponSlotId,
    String weaponId,
    List<MatchModuleDto> modules
) {
}
