package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Result of revoking a server identity.
 */
public record AdminServerIdentityRevokeResult(
    UUID serverId,
    String status,
    boolean updated
) {
}
