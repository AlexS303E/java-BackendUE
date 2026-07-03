package com.game.backend.runtimechanges.application;

import java.util.UUID;

/**
 * Result of a runtime preset operation.
 */
public record RuntimePresetChangeResult(
    UUID operationId,
    String status,
    Long resultRevision,
    UUID pendingChangeId,
    boolean duplicate,
    String errorCode
) {
}
