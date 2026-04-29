package com.game.backend.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос на ротацию refresh token.
 */
public record RefreshRequest(
    @NotBlank
    String refreshToken
) {
}
