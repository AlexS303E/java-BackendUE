package com.game.backend.admin.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/**
 * Достает текущего администратора из Spring SecurityContext.
 */
public final class CurrentAdmin {
    private CurrentAdmin() {
    }

    /**
     * Возвращает AdminIdentity или бросает 401, если endpoint вызван без admin auth.
     */
    public static AdminIdentity require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminIdentity admin)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication is required");
        }
        return admin;
    }
}
