package com.game.backend.matchprofile.api;

public record DependencyRevisionsDto(
    long weaponPresetRevision,
    long outfitPresetRevision,
    long accessRevision,
    long profileRevision
) {
}
