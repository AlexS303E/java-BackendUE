package com.game.backend.admin.api;

import java.util.UUID;

/**
 * Результат отзыва server identity.
 */
public record AdminServerIdentityRevokeResponse(
    UUID serverId,
    String status,
    boolean updated
) {
}
