package com.game.backend.matchprofile.api;

import java.util.List;

public record MatchWeaponDto(
    String weaponSlotId,
    String weaponId,
    List<MatchModuleDto> modules
) {
}
