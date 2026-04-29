package com.game.backend.postmatch.api;

import java.util.List;
import java.util.UUID;

/**
 * Список post-match pending changes игрока.
 */
public record PostMatchPendingChangesResponse(
    UUID playerId,
    List<PostMatchPendingChangeDto> changes
) {
}
