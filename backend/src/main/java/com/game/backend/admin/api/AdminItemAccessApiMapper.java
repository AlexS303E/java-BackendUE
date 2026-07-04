package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminItemAccessUpdateCommand;
import com.game.backend.admin.application.AdminItemAccessUpdateResult;

final class AdminItemAccessApiMapper {
    private AdminItemAccessApiMapper() {
    }

    static AdminItemAccessUpdateCommand toCommand(AdminItemAccessUpdateRequest request) {
        return new AdminItemAccessUpdateCommand(
            request.catalogVersion(),
            request.hidden(),
            request.lockedInShop(),
            request.lockedByQuest(),
            request.disabled(),
            request.disabledReason(),
            request.unlockHintCode(),
            request.unlockHintPayload(),
            request.reason(),
            request.eventType()
        );
    }

    static AdminItemAccessUpdateResponse toResponse(AdminItemAccessUpdateResult result) {
        return new AdminItemAccessUpdateResponse(
            result.playerId(),
            result.itemId(),
            result.catalogVersion(),
            result.accessRevision(),
            result.hidden(),
            result.lockedInShop(),
            result.lockedByQuest(),
            result.disabled(),
            result.disabledReason(),
            result.unlockHintCode(),
            result.unlockHintPayload(),
            result.playerCanUse(),
            result.ledgerEventId(),
            result.sanitizedWeaponPresets(),
            result.sanitizedOutfitPresets(),
            result.staleMatchProfiles(),
            result.duplicate()
        );
    }
}
