package com.game.backend.runtimeevents.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimeevents.api.RuntimeEventRequest;
import com.game.backend.runtimeevents.api.RuntimeEventResponse;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;
    private final OutboxService outboxService;

    public RuntimeEventsService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService,
        OutboxService outboxService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
        this.outboxService = outboxService;
    }

    /**
     * Записывает событие один раз по Idempotency-Key/event_id и публикует доменное событие в outbox.
     */
    @Transactional
    public RuntimeEventResponse record(ServerIdentity server, String idempotencyKey, RuntimeEventRequest request) {
        boolean matchAssigned = false;
        try {
            validateIdempotencyKey(idempotencyKey);
            validate(request);
            OffsetDateTime now = OffsetDateTime.now();
            String requestHash = requestHash(request);
            deleteExpiredIdempotencyRecord(server, idempotencyKey, now);

            ExistingIdempotencyRecord existing = existingIdempotencyRecord(server, idempotencyKey);
            if (existing != null) {
                return auditedResponse(server, request, replayExistingRecord(requestHash, existing));
            }

            serverMatchService.ensureAssignedForServerOperation(server, request.matchId(), "Runtime events");
            matchAssigned = true;

            if (eventExists(request.eventId())) {
                RuntimeEventResponse response = new RuntimeEventResponse(request.eventId(), "recorded", true);
                insertIdempotencyRecord(server, idempotencyKey, requestHash, response, now);
                return auditedResponse(server, request, response);
            }

            insertRuntimeEvent(server, request, now);
            if ("match_finished".equals(request.eventType())) {
                finishMatch(request.matchId(), now);
            }
            recordOutboxEvent(server, request, now);

            RuntimeEventResponse response = new RuntimeEventResponse(request.eventId(), "recorded", false);
            insertIdempotencyRecord(server, idempotencyKey, requestHash, response, now);
            return auditedResponse(server, request, response);
        } catch (ApiException exception) {
            auditFailure(server, request, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, request, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private RuntimeEventResponse auditedResponse(
        ServerIdentity server,
        RuntimeEventRequest request,
        RuntimeEventResponse response
    ) {
        auditSuccess(server, request, response);
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

    private void validate(RuntimeEventRequest request) {
        if (request.payloadSchemaVersion() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Only runtime event payload_schema_version=1 is supported");
        }
        if (!SUPPORTED_EVENT_TYPES.contains(request.eventType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported runtime event type: " + request.eventType());
        }
        if (request.payload().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Runtime event payload must not be empty");
        }
    }

    private boolean eventExists(UUID eventId) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM server_runtime_events WHERE event_id = ?)",
            Boolean.class,
            eventId
        );
        return Boolean.TRUE.equals(exists);
    }

    private void deleteExpiredIdempotencyRecord(ServerIdentity server, String idempotencyKey, OffsetDateTime now) {
        jdbcTemplate.update(
            """
                DELETE FROM api_idempotency_records
                WHERE operation_scope = ?
                  AND actor_id = ?
                  AND idempotency_key = ?
                  AND expires_at <= ?
                """,
            IDEMPOTENCY_SCOPE,
            server.serverId().toString(),
            idempotencyKey,
            now
        );
    }

    private ExistingIdempotencyRecord existingIdempotencyRecord(ServerIdentity server, String idempotencyKey) {
        List<ExistingIdempotencyRecord> records = jdbcTemplate.query(
            """
                SELECT request_hash, status_code, response_body::text AS response_body
                FROM api_idempotency_records
                WHERE operation_scope = ?
                  AND actor_id = ?
                  AND idempotency_key = ?
                """,
            (rs, rowNum) -> new ExistingIdempotencyRecord(
                rs.getString("request_hash"),
                rs.getInt("status_code"),
                rs.getString("response_body")
            ),
            IDEMPOTENCY_SCOPE,
            server.serverId().toString(),
            idempotencyKey
        );
        return records.isEmpty() ? null : records.getFirst();
    }

    private RuntimeEventResponse replayExistingRecord(String requestHash, ExistingIdempotencyRecord existing) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                "Idempotency-Key was reused with a different runtime event request body"
            );
        }
        RuntimeEventResponse stored = fromJson(existing.responseBody());
        return new RuntimeEventResponse(stored.eventId(), stored.status(), true);
    }

    private void insertIdempotencyRecord(
        ServerIdentity server,
        String idempotencyKey,
        String requestHash,
        RuntimeEventResponse response,
        OffsetDateTime now
    ) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO api_idempotency_records(
                      operation_scope,
                      actor_id,
                      route_fingerprint,
                      idempotency_key,
                      request_hash,
                      status_code,
                      response_body,
                      created_at,
                      expires_at
                    )
                    VALUES (?, ?, ?, ?, ?, 200, ?::jsonb, ?, ?)
                    """,
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

    private void insertRuntimeEvent(ServerIdentity server, RuntimeEventRequest request, OffsetDateTime now) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO server_runtime_events(
                      event_id,
                      match_id,
                      server_id,
                      event_seq,
                      event_type,
                      player_id,
                      payload,
                      payload_schema_version,
                      occurred_at,
                      received_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                request.eventId(),
                request.matchId(),
                server.serverId(),
                request.eventSeq(),
                request.eventType(),
                request.playerId(),
                toJson(request.payload()),
                request.payloadSchemaVersion(),
                request.occurredAt(),
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
        jdbcTemplate.update(
            """
                UPDATE server_matches
                SET status = 'finished',
                    finished_at = ?
                WHERE match_id = ?
                  AND status IN ('creating', 'running')
                """,
            now,
            matchId
        );
    }

    private void recordOutboxEvent(ServerIdentity server, RuntimeEventRequest request, OffsetDateTime now) {
        outboxService.record(
            "server_runtime_event.recorded",
            "server_runtime_event",
            request.eventId().toString(),
            1,
            Map.of(
                "event_id", request.eventId(),
                "event_seq", request.eventSeq(),
                "event_type", request.eventType(),
                "match_id", request.matchId(),
                "server_id", server.serverId(),
                "player_id", request.playerId() == null ? "" : request.playerId(),
                "occurred_at", request.occurredAt(),
                "source", "dedicated_server"
            ),
            now
        );
    }

    private void auditSuccess(ServerIdentity server, RuntimeEventRequest request, RuntimeEventResponse response) {
        serverAuditService.record(
            server,
            request.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            Map.of(
                "event_id", request.eventId(),
                "event_seq", request.eventSeq(),
                "event_type", request.eventType(),
                "duplicate", response.duplicate(),
                "status", response.status()
            )
        );
    }

    private void auditFailure(
        ServerIdentity server,
        RuntimeEventRequest request,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", request.eventId());
        payload.put("event_seq", request.eventSeq());
        payload.put("event_type", request.eventType());
        payload.put("match_id", request.matchId());
        payload.put("code", code);
        payload.put("status", status);
        serverAuditService.record(
            server,
            matchAssigned ? request.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            payload
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }

    private String requestHash(RuntimeEventRequest request) {
        return sha256(toJson(request));
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

    private RuntimeEventResponse fromJson(String payload) {
        try {
            return objectMapper.readValue(payload, RuntimeEventResponse.class);
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

    private record ExistingIdempotencyRecord(
        String requestHash,
        int statusCode,
        String responseBody
    ) {
    }
}
