package com.game.backend.access.api;

import java.util.List;
import java.util.UUID;

public record AccessResponse(
    UUID playerId,
    long catalogVersion,
    long accessRevision,
    List<AccessItemDto> items
) {
}
