package com.game.backend.admin.application;

import java.util.UUID;

/**
 * Command for revoking a server identity.
 */
public record AdminServerIdentityRevokeCommand(
    UUID serverId,
    String reason
) {
}
