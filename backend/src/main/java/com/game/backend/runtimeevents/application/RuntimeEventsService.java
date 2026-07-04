package com.game.backend.runtimeevents.application;

import com.game.backend.runtimeevents.repository.RuntimeEventsRepository;
import com.game.backend.runtimeevents.repository.RuntimeEventsRepository.ExistingIdempotencyRecord;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Принимает, валидирует и сохраняет runtime events от Dedicated Server.
 */
@Service
public class RuntimeEventsService {
    private static final String IDEMPOTENCY_SCOPE = "server_runtime_events";
    private static final String ROUTE_FINGERPRINT = "POST /server/runtime-events";
    private static final int IDEMPOTENCY_TTL_DAYS = 1;
    private static final String AUDIT_ACTION = "runtime_event.record";
    private static final String AUDIT_SCOPE = "runtime_event:write";
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "player_spawned",
        "loadout_applied",
        "item_used",
        "player_died",
        "match_finished"
    );

    private final RuntimeEventsRepository repository;
    private final ObjectMapper objectMapper;
    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;
    private final OutboxService outboxService;

    public RuntimeEventsService(
        RuntimeEventsRepository repository,
        ObjectMapper objectMapper,
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService,
        OutboxService outboxService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
        this.outboxService = outboxService;
    }

    /**
     * Записывает событие один раз по Idempotency-Key/event_id и публикует доменное событие в outbox.
     */
    @Transactional
    public RuntimeEventResult record(ServerIdentity server, String idempotencyKey, RuntimeEventCommand command) {
        boolean matchAssigned = false;
        try {
            validateIdempotencyKey(idempotencyKey);
            validate(command);
            OffsetDateTime now = OffsetDateTime.now();
            String requestHash = requestHash(command);
            deleteExpiredIdempotencyRecord(server, idempotencyKey, now);

            ExistingIdempotencyRecord existing = existingIdempotencyRecord(server, idempotencyKey);
            if (existing != null) {
                return auditedResponse(server, command, replayExistingRecord(requestHash, existing));
            }

            serverMatchService.ensureAssignedForServerOperation(server, command.matchId(), "Runtime events");
            matchAssigned = true;

            if (eventExists(command.eventId())) {
                RuntimeEventResult response = new RuntimeEventResult(command.eventId(), "recorded", true);
                insertIdempotencyRecord(server, idempotencyKey, requestHash, response, now);
                return auditedResponse(server, command, response);
            }

            insertRuntimeEvent(server, command, now);
            if ("match_finished".equals(command.eventType())) {
                finishMatch(command.matchId(), now);
            }
            recordOutboxEvent(server, command, now);

            RuntimeEventResult response = new RuntimeEventResult(command.eventId(), "recorded", false);
            insertIdempotencyRecord(server, idempotencyKey, requestHash, response, now);
            return auditedResponse(server, command, response);
        } catch (ApiException exception) {
            auditFailure(server, command, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, command, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private RuntimeEventResult auditedResponse(
        ServerIdentity server,
        RuntimeEventCommand command,
        RuntimeEventResult response
    ) {
        auditSuccess(server, command, response);
        return response;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required"
            );
        }
    }

    private void validate(RuntimeEventCommand command) {
        if (command.payloadSchemaVersion() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Only runtime event payload_schema_version=1 is supported");
        }
        if (!SUPPORTED_EVENT_TYPES.contains(command.eventType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported runtime event type: " + command.eventType());
        }
        if (command.payload().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Runtime event payload must not be empty");
        }
    }

    private boolean eventExists(UUID eventId) {
        return repository.eventExists(eventId);
    }

    private void deleteExpiredIdempotencyRecord(ServerIdentity server, String idempotencyKey, OffsetDateTime now) {
        repository.deleteExpiredIdempotencyRecord(
            IDEMPOTENCY_SCOPE,
            server.serverId().toString(),
            idempotencyKey,
            now
        );
    }

    private ExistingIdempotencyRecord existingIdempotencyRecord(ServerIdentity server, String idempotencyKey) {
        List<ExistingIdempotencyRecord> records = repository.findIdempotencyRecords(
            IDEMPOTENCY_SCOPE,
            server.serverId().toString(),
            idempotencyKey
        );
        return records.isEmpty() ? null : records.getFirst();
    }

    private RuntimeEventResult replayExistingRecord(String requestHash, ExistingIdempotencyRecord existing) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                "Idempotency-Key was reused with a different runtime event request body"
            );
        }
        RuntimeEventResult stored = fromJson(existing.responseBody());
        return new RuntimeEventResult(stored.eventId(), stored.status(), true);
    }

    private void insertIdempotencyRecord(
        ServerIdentity server,
        String idempotencyKey,
        String requestHash,
        RuntimeEventResult response,
        OffsetDateTime now
    ) {
        try {
            repository.insertIdempotencyRecord(
                IDEMPOTENCY_SCOPE,
                server.serverId().toString(),
                ROUTE_FINGERPRINT,
                idempotencyKey,
                requestHash,
                toJson(response),
                now,
                now.plusDays(IDEMPOTENCY_TTL_DAYS)
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                "Idempotency-Key was already recorded for another runtime event request"
            );
        }
    }

    private void insertRuntimeEvent(ServerIdentity server, RuntimeEventCommand command, OffsetDateTime now) {
        try {
            repository.insertRuntimeEvent(
                command.eventId(),
                command.matchId(),
                server.serverId(),
                command.eventSeq(),
                command.eventType(),
                command.playerId(),
                toJson(command.payload()),
                command.payloadSchemaVersion(),
                command.occurredAt(),
                now
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUNTIME_EVENT_SEQ_ALREADY_USED",
                "Runtime event sequence was already used for this match and server"
            );
        }
    }

    private void finishMatch(UUID matchId, OffsetDateTime now) {
        repository.markMatchFinished(matchId, now);
    }

    private void recordOutboxEvent(ServerIdentity server, RuntimeEventCommand command, OffsetDateTime now) {
        outboxService.recordServerRuntimeEventRecorded(
            command.eventId(),
            command.eventSeq(),
            command.eventType(),
            command.matchId(),
            server.serverId(),
            command.playerId(),
            command.occurredAt(),
            now
        );
    }

    private void auditSuccess(ServerIdentity server, RuntimeEventCommand command, RuntimeEventResult response) {
        serverAuditService.record(
            server,
            command.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            Map.of(
                "event_id", command.eventId(),
                "event_seq", command.eventSeq(),
                "event_type", command.eventType(),
                "duplicate", response.duplicate(),
                "status", response.status()
            )
        );
    }

    private void auditFailure(
        ServerIdentity server,
        RuntimeEventCommand command,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", command.eventId());
        payload.put("event_seq", command.eventSeq());
        payload.put("event_type", command.eventType());
        payload.put("match_id", command.matchId());
        payload.put("code", code);
        payload.put("status", status);
        serverAuditService.record(
            server,
            matchAssigned ? command.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            payload
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }

    private String requestHash(RuntimeEventCommand command) {
        return sha256(toJson(command));
    }

    private String toJson(Map<String, Object> payload) {
        return toJson((Object) payload);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RUNTIME_EVENT_SERIALIZATION_FAILED", "Unable to serialize runtime event payload");
        }
    }

    private RuntimeEventResult fromJson(String payload) {
        try {
            return objectMapper.readValue(payload, RuntimeEventResult.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RUNTIME_EVENT_SERIALIZATION_FAILED", "Unable to deserialize runtime event response");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_HASH_FAILED", "Unable to hash runtime event request");
        }
    }

}
