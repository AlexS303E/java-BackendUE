package com.game.backend.runtimechanges.api;

import java.util.UUID;

public record RuntimePresetChangeResponse(
    UUID operationId,
    String status,
    Long resultRevision,
    UUID pendingChangeId,
    boolean duplicate,
    String errorCode
) {
}
