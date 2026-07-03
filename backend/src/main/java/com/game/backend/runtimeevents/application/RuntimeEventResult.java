package com.game.backend.runtimeevents.application;

import java.util.UUID;

/**
 * Result of recording a runtime event.
 */
public record RuntimeEventResult(
    UUID eventId,
    String status,
    boolean duplicate
) {
}
