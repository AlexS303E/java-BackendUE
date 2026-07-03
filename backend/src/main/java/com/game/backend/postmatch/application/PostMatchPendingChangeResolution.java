package com.game.backend.postmatch.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of applying or rejecting a post-match pending change.
 */
public record PostMatchPendingChangeResolution(
    UUID changeId,
    String status,
    Long resultRevision,
    OffsetDateTime resolvedAt
) {
}
