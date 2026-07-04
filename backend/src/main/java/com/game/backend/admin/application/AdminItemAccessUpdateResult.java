package com.game.backend.admin.application;

import java.util.Map;
import java.util.UUID;

/**
 * Result of an admin item access update.
 */
public record AdminItemAccessUpdateResult(
    UUID playerId,
    String itemId,
    long catalogVersion,
    long accessRevision,
    boolean hidden,
    boolean lockedInShop,
    boolean lockedByQuest,
    boolean disabled,
    String disabledReason,
    String unlockHintCode,
    Map<String, Object> unlockHintPayload,
    boolean playerCanUse,
    UUID ledgerEventId,
    int sanitizedWeaponPresets,
    int sanitizedOutfitPresets,
    int staleMatchProfiles,
    boolean duplicate
) {
}
