package com.game.backend.matchprofile.api;

/**
 * Ревизии зависимостей, из которых собран match profile.
 */
public record DependencyRevisionsDto(
    long weaponPresetRevision,
    long outfitPresetRevision,
    long accessRevision,
    long profileRevision
) {
}
