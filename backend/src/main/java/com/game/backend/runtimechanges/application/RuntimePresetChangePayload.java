package com.game.backend.runtimechanges.application;

import java.util.List;

/**
 * Runtime preset operation payload used by application services.
 */
public record RuntimePresetChangePayload(
    Integer schemaVersion,
    List<RuntimePresetChangeStep> changes
) {
}
