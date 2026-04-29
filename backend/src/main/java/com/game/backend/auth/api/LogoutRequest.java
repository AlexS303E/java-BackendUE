package com.game.backend.auth.api;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
    @NotBlank
    String refreshToken
) {
}
