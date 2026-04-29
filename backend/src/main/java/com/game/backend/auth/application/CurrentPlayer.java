package com.game.backend.auth.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

public final class CurrentPlayer {
    private CurrentPlayer() {
    }

    public static AuthenticatedPlayer require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPlayer player)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        return player;
    }
}
