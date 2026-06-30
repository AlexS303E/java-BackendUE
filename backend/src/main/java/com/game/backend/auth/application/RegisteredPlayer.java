package com.game.backend.auth.application;

import java.util.UUID;

/**
 * Newly registered player account state.
 */
public record RegisteredPlayer(
    UUID playerId,
    String loginName,
    String status,
    boolean needsBootstrap
) {
}
