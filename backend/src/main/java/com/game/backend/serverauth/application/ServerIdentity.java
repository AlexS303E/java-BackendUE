package com.game.backend.serverauth.application;

import java.util.Set;
import java.util.UUID;

/**
 * Principal Dedicated Server после успешной server auth проверки.
 */
public record ServerIdentity(
    UUID serverId,
    String realmId,
    String serverBuildId,
    Set<String> allowedScopes
) {
    /**
     * Проверяет, есть ли у server identity требуемый scope для endpoint.
     */
    public boolean hasScope(String scope) {
        return allowedScopes.contains(scope);
    }
}
