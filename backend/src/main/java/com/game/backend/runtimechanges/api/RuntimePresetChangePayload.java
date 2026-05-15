package com.game.backend.runtimechanges.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Полезная нагрузка runtime preset operation: версия схемы и список atomic changes.
 */
public record RuntimePresetChangePayload(
    @NotNull
    @Min(1)
    @Max(1)
    Integer schemaVersion,

    @NotEmpty
    @Size(max = 100)
    List<@Valid RuntimePresetChangeStep> changes
) {
}
