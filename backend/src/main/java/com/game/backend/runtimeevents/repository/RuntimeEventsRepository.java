package com.game.backend.runtimeevents.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class RuntimeEventsRepository extends JdbcRepository {
    public record ExistingIdempotencyRecord(
        String requestHash,
        int statusCode,
        String responseBody
    ) {
    }

    public RuntimeEventsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public boolean eventExists(UUID eventId) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM server_runtime_events
                  WHERE event_id = ?
                )
                """,
            Boolean.class,
            eventId
        );
        return Boolean.TRUE.equals(exists);
    }

    public void deleteExpiredIdempotencyRecord(
        String operationScope,
        String actorId,
        String idempotencyKey,
        OffsetDateTime now
    ) {
        update(
            """
                DELETE FROM api_idempotency_records
                WHERE operation_scope = ?
                  AND actor_id = ?
                  AND idempotency_key = ?
                  AND expires_at <= ?
                """,
            operationScope,
            actorId,
            idempotencyKey,
            now
        );
    }

    public List<ExistingIdempotencyRecord> findIdempotencyRecords(
        String operationScope,
        String actorId,
        String idempotencyKey
    ) {
        return query(
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
            operationScope,
            actorId,
            idempotencyKey
        );
    }

    public void insertIdempotencyRecord(
        String operationScope,
        String actorId,
        String routeFingerprint,
        String idempotencyKey,
        String requestHash,
        String responseBodyJson,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    ) {
        update(
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
            operationScope,
            actorId,
            routeFingerprint,
            idempotencyKey,
            requestHash,
            responseBodyJson,
            now,
            expiresAt
        );
    }

    public void insertRuntimeEvent(
        UUID eventId,
        UUID matchId,
        UUID serverId,
        long eventSeq,
        String eventType,
        UUID playerId,
        String payloadJson,
        int payloadSchemaVersion,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt
    ) {
        update(
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
            eventId,
            matchId,
            serverId,
            eventSeq,
            eventType,
            playerId,
            payloadJson,
            payloadSchemaVersion,
            occurredAt,
            receivedAt
        );
    }

    public void markMatchFinished(UUID matchId, OffsetDateTime now) {
        update(
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
}
