package com.game.backend.outbox.application;

public interface OutboxEventHandler {
    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
