package com.game.backend.admin.api;

import java.util.UUID;

/**
 * Результат пересборки access projection игрока.
 */
public record AdminProjectionRebuildResponse(
    UUID playerId,
    long accessRevision,
    int itemsRebuilt,
    int ledgerEventsApplied,
    int staleMatchProfiles,
    UUID lastLedgerEventId
) {
}
