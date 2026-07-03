package com.game.backend.postmatch.application;

import java.util.List;
import java.util.UUID;

/**
 * Player pending changes page.
 */
public record PostMatchPendingChangePage(
    UUID playerId,
    List<PostMatchPendingChangeEntry> changes
) {
}
