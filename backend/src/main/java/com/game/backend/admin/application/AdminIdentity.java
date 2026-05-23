package com.game.backend.admin.application;

import java.util.Set;

public record AdminIdentity(
    String actorId,
    Set<String> roles
) {
    public AdminIdentity(String actorId) {
        this(actorId, Set.of("status", "access", "catalog", "ops", "security"));
    }
}
