package com.game.backend.catalog.application;

/**
 * Catalog rule that allows one module for a weapon mount.
 */
public record AllowedModule(
    String mountId,
    String moduleId,
    long catalogVersion
) {
}
