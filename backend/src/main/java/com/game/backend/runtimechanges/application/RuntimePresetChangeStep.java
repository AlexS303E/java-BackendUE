package com.game.backend.runtimechanges.application;

/**
 * One runtime preset operation step.
 */
public record RuntimePresetChangeStep(
    String op,
    String weaponSlotId,
    String weaponId,
    String mountId,
    String moduleId
) {
}
