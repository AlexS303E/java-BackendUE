package com.game.backend.postmatch.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Pending change that a player can resolve after a match.
 */
public record PostMatchPendingChangeEntry(
    UUID changeId,
    UUID matchId,
    String classTag,
    int weaponPresetSlot,
    long baseWeaponPresetRevision,
    Long currentConflictingRevision,
    String reasonCode,
    String status,
    Map<String, Object> payload,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt,
    OffsetDateTime resolvedAt
) {
}
