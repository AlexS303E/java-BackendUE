package com.game.backend.matchprofile.application;

/**
 * Revisions used to build a match profile snapshot.
 */
public record MatchProfileDependencyRevisions(
    long weaponPresetRevision,
    long outfitPresetRevision,
    long accessRevision,
    long profileRevision
) {
}
