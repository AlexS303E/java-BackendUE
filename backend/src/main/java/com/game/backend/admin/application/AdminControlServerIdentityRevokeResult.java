package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Result of revoking a server identity from admin control.
 */
public record AdminControlServerIdentityRevokeResult(
    UUID serverId,
    String status
) {
}
