package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Command for manual player cache/profile invalidation.
 */
public record AdminCacheInvalidateCommand(
    UUID playerId,
    String reason
) {
}
