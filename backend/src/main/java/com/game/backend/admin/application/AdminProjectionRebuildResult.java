package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Result of rebuilding a player's item access projection.
 */
public record AdminProjectionRebuildResult(
    UUID playerId,
    long accessRevision,
    int itemsRebuilt,
    int ledgerEventsApplied,
    int staleMatchProfiles,
    UUID lastLedgerEventId
) {
}
