package com.game.backend.postmatch.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Результат применения или отклонения pending change.
 */
public record PostMatchPendingChangeResolutionResponse(
    UUID changeId,
    String status,
    Long resultRevision,
    OffsetDateTime resolvedAt
) {
}
