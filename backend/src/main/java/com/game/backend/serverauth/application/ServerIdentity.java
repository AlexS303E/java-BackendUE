package com.game.backend.serverauth.application;

import java.util.Set;
import java.util.UUID;

public record ServerIdentity(
    UUID serverId,
    String realmId,
    String serverBuildId,
    Set<String> allowedScopes
) {
    public boolean hasScope(String scope) {
        return allowedScopes.contains(scope);
    }
}
