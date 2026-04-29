package com.game.backend.catalog.api;

/**
 * Разрешенная установка модуля в конкретный weapon mount.
 */
public record AllowedModuleDto(
    String mountId,
    String moduleId,
    long catalogVersion
) {
}
