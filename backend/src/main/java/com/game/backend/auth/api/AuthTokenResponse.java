package com.game.backend.auth.api;

import java.util.UUID;

public record AuthTokenResponse(
    UUID playerId,
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    long refreshExpiresIn
) {
}
