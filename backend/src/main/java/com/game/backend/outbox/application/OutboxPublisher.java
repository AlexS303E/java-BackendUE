package com.game.backend.outbox.application;

/**
 * Абстракция доставки outbox event во внешний транспорт.
 */
public interface OutboxPublisher {
    void publish(OutboxEvent event);
}
