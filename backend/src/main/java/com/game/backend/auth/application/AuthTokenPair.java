package com.game.backend.auth.application;

import java.util.UUID;

/**
 * Issued access/refresh token pair for an authenticated player.
 */
public record AuthTokenPair(
    UUID playerId,
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    long refreshExpiresIn
) {
}
