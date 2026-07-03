package com.game.backend.presets.application;

/**
 * Selected module for a weapon mount.
 */
public record ModuleSelection(
    String mountId,
    String moduleId
) {
}
