package com.game.backend.postmatch.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Pending change, который игрок должен решить после матча.
 */
public record PostMatchPendingChangeDto(
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
