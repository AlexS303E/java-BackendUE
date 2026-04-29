package com.game.backend.runtimechanges.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Полезная нагрузка runtime preset operation: версия схемы и список atomic changes.
 */
public record RuntimePresetChangePayload(
    @NotNull
    Integer schemaVersion,

    @NotEmpty
    List<@Valid RuntimePresetChangeStep> changes
) {
}
