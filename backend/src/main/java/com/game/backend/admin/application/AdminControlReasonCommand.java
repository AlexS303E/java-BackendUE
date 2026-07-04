package com.game.backend.admin.application;

/**
 * Reason/comment command shared by manual admin control actions.
 */
public record AdminControlReasonCommand(
    String reason,
    String comment
) {
}
