package com.game.backend.auth.api;

import java.util.UUID;

public record RegisterResponse(
    UUID playerId,
    String loginName,
    String status,
    boolean needsBootstrap
) {
}
