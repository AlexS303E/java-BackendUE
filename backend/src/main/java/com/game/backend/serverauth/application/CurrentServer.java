package com.game.backend.serverauth.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

public final class CurrentServer {
    private CurrentServer() {
    }

    public static ServerIdentity require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ServerIdentity server)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Server authentication is required");
        }
        return server;
    }
}
