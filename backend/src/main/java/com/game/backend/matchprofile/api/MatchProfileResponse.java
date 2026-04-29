package com.game.backend.matchprofile.api;

import java.util.List;
import java.util.UUID;

/**
 * Готовый loadout snapshot игрока, который можно отдать Dedicated Server.
 */
public record MatchProfileResponse(
    int schemaVersion,
    UUID playerId,
    String realmId,
    long catalogVersion,
    String classTag,
    String teamTag,
    int weaponPresetSlot,
    int outfitPresetSlot,
    List<MatchWeaponDto> weapons,
    List<MatchOutfitItemDto> outfit,
    List<String> sanitizedWarnings,
    DependencyRevisionsDto dependencyRevisions
) {
}
