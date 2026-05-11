package com.game.backend.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Команда администратора на изменение projection доступа игрока к одному предмету.
 */
public record AdminItemAccessUpdateRequest(
    @NotNull
    Long catalogVersion,

    @NotNull
    Boolean hidden,

    @NotNull
    Boolean lockedInShop,

    @NotNull
    Boolean lockedByQuest,

    @NotNull
    Boolean disabled,

    String disabledReason,

    String unlockHintCode,

    Map<String, Object> unlockHintPayload,

    @NotBlank
    String reason,

    String eventType
) {
}
