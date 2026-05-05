package com.game.backend.admin.api;

import java.util.UUID;

/**
 * Результат ручной инвалидации player-level cache/profile snapshots.
 */
public record AdminCacheInvalidateResponse(
    UUID playerId,
    int staleMatchProfiles
) {
}
