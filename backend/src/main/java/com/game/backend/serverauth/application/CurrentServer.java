package com.game.backend.serverauth.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/**
 * Утилита для извлечения текущей server identity из Spring SecurityContext.
 */
public final class CurrentServer {
    private CurrentServer() {
    }

    /**
     * Возвращает ServerIdentity или бросает 401, если endpoint вызван не через server auth.
     */
    public static ServerIdentity require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ServerIdentity server)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Server authentication is required");
        }
        return server;
    }
}
