package com.game.backend.outbox.application;

import com.game.backend.outbox.repository.OutboxRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Записывает доменные события в outbox_events внутри текущей транзакции.
 */
@Service
public class OutboxService {
    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Создает pending outbox event, который позже сможет обработать отдельный worker.
     */
    public void record(
        String eventType,
        String aggregateType,
        String aggregateId,
        int payloadSchemaVersion,
        Map<String, Object> payload,
        OffsetDateTime now
    ) {
        repository.insertPendingEvent(
            UUID.randomUUID(),
            eventType,
            aggregateType,
            aggregateId,
            toJson(payload),
            payloadSchemaVersion,
            now
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OUTBOX_PAYLOAD_SERIALIZATION_FAILED", "Unable to serialize outbox payload");
        }
    }
}
