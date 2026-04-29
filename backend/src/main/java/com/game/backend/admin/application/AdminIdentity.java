package com.game.backend.admin.application;

/**
 * Минимальная admin identity для MVP-админских операций.
 */
public record AdminIdentity(
    String actorId
) {
}
