package com.game.backend.runtimeevents.api;

import java.util.UUID;

/**
 * Результат записи runtime event.
 */
public record RuntimeEventResponse(
    UUID eventId,
    String status,
    boolean duplicate
) {
}
