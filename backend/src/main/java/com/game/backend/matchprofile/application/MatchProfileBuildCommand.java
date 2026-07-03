package com.game.backend.matchprofile.application;

import java.util.List;
import java.util.UUID;

/**
 * Match profile build command accepted from a dedicated server.
 */
public record MatchProfileBuildCommand(
    UUID matchId,
    UUID playerId,
    String realmId,
    String classTag,
    String teamTag,
    int weaponPresetSlot,
    int outfitPresetSlot,
    List<Long> supportedCatalogVersions,
    Long preferredCatalogVersion,
    String serverBuildId,
    String gameModeId
) {
}
