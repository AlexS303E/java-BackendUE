package com.game.backend.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Запрос на вход по login/password.
 */
public record LoginRequest(
    @NotBlank
    String loginName,

    @NotBlank
    String password
) {
}
