package com.game.backend.admin.application;

import java.util.Map;

/**
 * Admin command that updates one player item access projection.
 */
public record AdminItemAccessUpdateCommand(
    Long catalogVersion,
    Boolean hidden,
    Boolean lockedInShop,
    Boolean lockedByQuest,
    Boolean disabled,
    String disabledReason,
    String unlockHintCode,
    Map<String, Object> unlockHintPayload,
    String reason,
    String eventType
) {
}
