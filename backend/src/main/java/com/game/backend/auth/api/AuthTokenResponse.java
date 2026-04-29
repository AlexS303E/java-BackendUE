package com.game.backend.auth.api;

import java.util.UUID;

/**
 * Пара токенов, которую получает игрок после login или refresh.
 */
public record AuthTokenResponse(
    UUID playerId,
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    long refreshExpiresIn
) {
}
