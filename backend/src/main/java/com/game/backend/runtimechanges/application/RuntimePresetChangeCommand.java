package com.game.backend.runtimechanges.application;

import java.util.UUID;

/**
 * Runtime preset change command accepted from a dedicated server.
 */
public record RuntimePresetChangeCommand(
    UUID operationId,
    Long operationSeq,
    UUID matchId,
    UUID playerId,
    String classTag,
    int weaponPresetSlot,
    Long baseWeaponPresetRevision,
    RuntimePresetChangePayload runtimeChangePayload
) {
}
