package com.game.backend.catalog.api;

public record AllowedModuleDto(
    String mountId,
    String moduleId,
    long catalogVersion
) {
}
