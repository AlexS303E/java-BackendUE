package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Command for rebuilding a player's item access projection.
 */
public record AdminProjectionRebuildCommand(
    UUID playerId,
    String reason
) {
}
