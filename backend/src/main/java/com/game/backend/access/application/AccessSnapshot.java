package com.game.backend.access.application;

import java.util.List;
import java.util.UUID;

/**
 * Player access projection for a selected catalog version.
 */
public record AccessSnapshot(
    UUID playerId,
    long catalogVersion,
    long accessRevision,
    List<AccessItem> items
) {
}
