package com.game.backend.auth.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/**
 * Утилита для безопасного извлечения текущего игрока из Spring SecurityContext.
 */
public final class CurrentPlayer {
    private CurrentPlayer() {
    }

    /**
     * Возвращает AuthenticatedPlayer или бросает 401, если endpoint вызван без player auth.
     */
    public static AuthenticatedPlayer require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPlayer player)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        return player;
    }
}
