package com.game.backend.matchprofile.application;

import java.util.List;
import java.util.UUID;

/**
 * Server-ready match profile snapshot built from durable presets and catalog rules.
 */
public record MatchProfileSnapshot(
    int schemaVersion,
    UUID playerId,
    String realmId,
    long catalogVersion,
    String classTag,
    String teamTag,
    int weaponPresetSlot,
    int outfitPresetSlot,
    List<MatchProfileWeapon> weapons,
    List<MatchProfileOutfitItem> outfit,
    List<String> sanitizedWarnings,
    MatchProfileDependencyRevisions dependencyRevisions
) {
}
