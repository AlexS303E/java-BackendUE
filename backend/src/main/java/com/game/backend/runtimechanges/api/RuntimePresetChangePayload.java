package com.game.backend.runtimechanges.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RuntimePresetChangePayload(
    @NotNull
    Integer schemaVersion,

    @NotEmpty
    List<@Valid RuntimePresetChangeStep> changes
) {
}
