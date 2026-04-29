package com.game.backend.auth.application;

import java.util.UUID;

public record AuthenticatedPlayer(
    UUID playerId,
    String loginName
) {
}
