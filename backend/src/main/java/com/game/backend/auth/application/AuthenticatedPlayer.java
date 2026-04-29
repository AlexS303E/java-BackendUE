package com.game.backend.auth.application;

import java.util.UUID;

/**
 * Principal игрока, который кладется в Spring SecurityContext после проверки JWT.
 */
public record AuthenticatedPlayer(
    UUID playerId,
    String loginName
) {
}
