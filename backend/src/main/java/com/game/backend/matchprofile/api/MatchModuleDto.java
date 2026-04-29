package com.game.backend.matchprofile.api;

/**
 * Модуль, включенный в match profile для выбранного оружия.
 */
public record MatchModuleDto(
    String mountId,
    String moduleId
) {
}
