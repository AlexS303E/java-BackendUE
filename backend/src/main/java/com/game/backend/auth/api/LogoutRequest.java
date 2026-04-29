package com.game.backend.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос на отзыв refresh token.
 */
public record LogoutRequest(
    @NotBlank
    String refreshToken
) {
}
