package com.game.backend.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на создание нового player account.
 */
public record RegisterRequest(
    @NotBlank
    @Size(min = 3, max = 64)
    String loginName,

    @NotBlank
    @Size(min = 8, max = 256)
    String password
) {
}
