package com.game.backend.access.application;

import java.util.Map;

/**
 * Player access state for one catalog item.
 */
public record AccessItem(
    String itemId,
    String itemType,
    String displayName,
    boolean hidden,
    boolean lockedInShop,
    boolean lockedByQuest,
    boolean disabled,
    String disabledReason,
    String unlockHintCode,
    Map<String, Object> unlockHintPayload,
    boolean playerCanUse
) {
}
