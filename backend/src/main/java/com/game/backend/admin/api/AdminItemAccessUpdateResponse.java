package com.game.backend.admin.api;

import java.util.Map;
import java.util.UUID;

/**
 * Итог admin-операции над доступом к предмету и новая revision projection.
 */
public record AdminItemAccessUpdateResponse(
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
