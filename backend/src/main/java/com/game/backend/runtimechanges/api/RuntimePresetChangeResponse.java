package com.game.backend.runtimechanges.api;

import java.util.UUID;

/**
 * Результат runtime preset operation: applied, conflict или replay duplicate.
 */
public record RuntimePresetChangeResponse(
    UUID operationId,
    String status,
    Long resultRevision,
    UUID pendingChangeId,
    boolean duplicate,
    String errorCode
) {
}
