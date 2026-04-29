package com.game.backend.auth.api;

import java.util.UUID;

/**
 * Результат регистрации игрока.
 */
public record RegisterResponse(
    UUID playerId,
    String loginName,
    String status,
    boolean needsBootstrap
) {
}
