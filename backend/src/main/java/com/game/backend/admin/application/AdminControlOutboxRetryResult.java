package com.game.backend.admin.application;

/**
 * Result of retrying failed outbox events from admin control.
 */
public record AdminControlOutboxRetryResult(
    int retried
) {
}
