package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Result of manually invalidating a player's cache/profile snapshots.
 */
public record AdminControlPlayerCacheInvalidationResult(
    UUID playerId,
    int staleMatchProfiles
) {
}
