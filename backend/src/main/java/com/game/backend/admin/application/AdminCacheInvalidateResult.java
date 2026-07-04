package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Result of manual player cache/profile invalidation.
 */
public record AdminCacheInvalidateResult(
    UUID playerId,
    int staleMatchProfiles
) {
}
