package com.game.backend.access.api;

import java.util.Map;

/**
 * Состояние доступа игрока к одному предмету каталога.
 */
public record AccessItemDto(
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
