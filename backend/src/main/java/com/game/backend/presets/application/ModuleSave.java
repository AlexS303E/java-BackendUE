package com.game.backend.presets.application;

/**
 * Module save command for one weapon mount.
 */
public record ModuleSave(
    String mountId,
    String moduleId
) {
}
