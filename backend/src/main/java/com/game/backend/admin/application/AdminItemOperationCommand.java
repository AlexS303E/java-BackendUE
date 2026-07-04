package com.game.backend.admin.application;

import java.util.Map;
import java.util.UUID;

/**
 * Command for explicit admin item mutation endpoints.
 */
public record AdminItemOperationCommand(
    UUID playerId,
    String itemId,
    Long catalogVersion,
    String reason,
    String disabledReason,
    String unlockHintCode,
    Map<String, Object> unlockHintPayload
) {
}
