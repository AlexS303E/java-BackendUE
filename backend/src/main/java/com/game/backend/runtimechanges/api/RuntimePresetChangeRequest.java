package com.game.backend.runtimechanges.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RuntimePresetChangeRequest(
    @NotNull
    UUID operationId,

    @NotNull
    Long operationSeq,

    @NotNull
    UUID matchId,

    @NotNull
    UUID playerId,

    @NotBlank
    String classTag,

    int weaponPresetSlot,

    @NotNull
    Long baseWeaponPresetRevision,

    @NotNull
    @Valid
    RuntimePresetChangePayload runtimeChangePayload
) {
}
