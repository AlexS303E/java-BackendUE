package com.game.backend.runtimechanges.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeResponse;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
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
import java.util.UUID;

/**
 * Применяет runtime preset changes от DS и фиксирует conflict как post-match pending change.
 */
@Service
public class RuntimePresetChangeService {
    private static final int PENDING_TTL_DAYS = 7;
    private static final String AUDIT_ACTION = "runtime_preset_change.submit";
    private static final String AUDIT_SCOPE = "runtime_preset_change:write";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;
    private final WeaponPresetRuntimeChangeApplier runtimeChangeApplier;
    private final OutboxService outboxService;
    private final PlayerNotificationService playerNotificationService;

    public RuntimePresetChangeService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService,
        WeaponPresetRuntimeChangeApplier runtimeChangeApplier,
        OutboxService outboxService,
        PlayerNotificationService playerNotificationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
        this.runtimeChangeApplier = runtimeChangeApplier;
        this.outboxService = outboxService;
        this.playerNotificationService = playerNotificationService;
    }

    /**
     * Проверяет идемпотентность, владение матчем, ревизию preset и применяет операцию.
     */
    @Transactional
    public RuntimePresetChangeResponse submit(
        ServerIdentity server,
        String idempotencyKey,
        RuntimePresetChangeRequest request
    ) {
        boolean matchAssigned = false;
        try {
            validateIdempotencyKey(idempotencyKey, request);
            validatePayload(request.runtimeChangePayload());

            String requestHash = requestHash(request);
            serverMatchService.ensureAssignedForRuntimeChange(server, request);
            matchAssigned = true;
            OffsetDateTime now = OffsetDateTime.now();

            // 1. Check for existing operation (idempotency) before locking/ordering
            ExistingOperation existing = existingOperation(request.operationId());
            if (existing != null) {
                return auditedResponse(server, request, replayExistingOperation(request, requestHash, existing));
            }

            // 2. Lock stream and validate ordering
            lockOperationStream(request);
            ensureOperationOrdering(request);

            // 3. INSERT operation with 'processing' status
            int inserted = tryInsertOperation(request, requestHash, now);
            if (inserted == 0) {
                // Race: concurrent thread inserted between SELECT and INSERT
                existing = existingOperation(request.operationId());
                return auditedResponse(server, request, replayExistingOperation(request, requestHash, existing));
            }

            // 3. Lock preset and apply
            PresetHeader preset = lockWeaponPreset(request);
            if (preset.revision() != request.baseWeaponPresetRevision()) {
                UUID pendingChangeId = createPendingChange(request, preset.revision(), now);
                updateOperationStatus(request.operationId(), "conflict", null, pendingChangeId, now);
                updateOperationStream(request);
                recordPendingChangeCreated(request, preset.revision(), pendingChangeId, now);
                return auditedResponse(
                    server, request,
                    new RuntimePresetChangeResponse(
                        request.operationId(), "conflict", null, pendingChangeId, false,
                        "PRESET_REVISION_CONFLICT"
                    )
                );
            }

            try {
                runtimeChangeApplier.apply(
                    request.playerId(), request.classTag(), request.weaponPresetSlot(),
                    preset.catalogVersion(), request.runtimeChangePayload(), now
                );
            } catch (ApiException exception) {
                String opStatus = exception.status() == HttpStatus.UNPROCESSABLE_ENTITY ? "rejected" : "failed";
                String reason = exception.code();
                updateOperationStatus(request.operationId(), opStatus, null, null, now);
                updateOperationStream(request);
                recordRuntimePresetFailed(request, preset.catalogVersion(), opStatus, reason, now);
                return auditedResponse(
                    server, request,
                    new RuntimePresetChangeResponse(
                        request.operationId(), opStatus, null, null, false, reason
                    )
                );
            }

            long resultRevision = preset.revision() + 1;
            jdbcTemplate.update(
                """
                    UPDATE player_weapon_presets
                    SET revision = ?,
                        sanitized = false,
                        updated_at = ?
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                    """,
                resultRevision, now,
                request.playerId(), request.classTag(), request.weaponPresetSlot(), preset.catalogVersion()
            );

            updateOperationStatus(request.operationId(), "applied", resultRevision, null, now);
            updateOperationStream(request);
            recordRuntimePresetApplied(request, preset.catalogVersion(), resultRevision, now);
            return auditedResponse(
                server, request,
                new RuntimePresetChangeResponse(
                    request.operationId(), "applied", resultRevision, null, false, null
                )
            );
        } catch (ApiException exception) {
            auditFailure(server, request, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, request, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private RuntimePresetChangeResponse auditedResponse(
        ServerIdentity server,
        RuntimePresetChangeRequest request,
        RuntimePresetChangeResponse response
    ) {
        auditSuccess(server, request, response);
        return response;
    }

    private void auditSuccess(
        ServerIdentity server,
        RuntimePresetChangeRequest request,
        RuntimePresetChangeResponse response
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("match_id", request.matchId());
        payload.put("operation_id", request.operationId());
        payload.put("operation_seq", request.operationSeq());
        payload.put("player_id", request.playerId());
        payload.put("class_tag", request.classTag());
        payload.put("weapon_preset_slot", request.weaponPresetSlot());
        payload.put("status", response.status());
        payload.put("duplicate", response.duplicate());
        if (response.resultRevision() != null) {
            payload.put("result_revision", response.resultRevision());
        }
        if (response.pendingChangeId() != null) {
            payload.put("pending_change_id", response.pendingChangeId());
        }

        serverAuditService.record(
            server,
            request.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            payload
        );
    }

    private void auditFailure(
        ServerIdentity server,
        RuntimePresetChangeRequest request,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        serverAuditService.record(
            server,
            matchAssigned ? request.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            Map.of(
                "match_id", request.matchId(),
                "operation_id", request.operationId(),
                "operation_seq", request.operationSeq(),
                "player_id", request.playerId(),
                "class_tag", request.classTag(),
                "weapon_preset_slot", request.weaponPresetSlot(),
                "code", code,
                "status", status
            )
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }

    private void recordRuntimePresetApplied(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        long resultRevision,
        OffsetDateTime now
    ) {
        outboxService.record(
            "weapon_preset.runtime_changed",
            "weapon_preset",
            weaponPresetAggregateId(request.playerId(), request.classTag(), request.weaponPresetSlot(), catalogVersion),
            1,
            Map.of(
                "player_id", request.playerId(),
                "match_id", request.matchId(),
                "operation_id", request.operationId(),
                "class_tag", request.classTag(),
                "preset_slot", request.weaponPresetSlot(),
                "catalog_version", catalogVersion,
                "base_revision", request.baseWeaponPresetRevision(),
                "revision", resultRevision,
                "source", "runtime"
            ),
            now
        );
    }

    private void recordRuntimePresetFailed(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        String status,
        String reason,
        OffsetDateTime now
    ) {
        outboxService.record(
            "weapon_preset.runtime_failed",
            "weapon_preset",
            weaponPresetAggregateId(request.playerId(), request.classTag(), request.weaponPresetSlot(), catalogVersion),
            1,
            Map.of(
                "player_id", request.playerId(),
                "match_id", request.matchId(),
                "operation_id", request.operationId(),
                "class_tag", request.classTag(),
                "preset_slot", request.weaponPresetSlot(),
                "catalog_version", catalogVersion,
                "base_revision", request.baseWeaponPresetRevision(),
                "status", status,
                "reason", reason,
                "source", "runtime"
            ),
            now
        );
    }

    private void recordPendingChangeCreated(
        RuntimePresetChangeRequest request,
        long currentRevision,
        UUID pendingChangeId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", request.playerId(),
            "match_id", request.matchId(),
            "operation_id", request.operationId(),
            "class_tag", request.classTag(),
            "preset_slot", request.weaponPresetSlot(),
            "base_revision", request.baseWeaponPresetRevision(),
            "current_revision", currentRevision,
            "pending_change_id", pendingChangeId,
            "status", "pending",
            "source", "runtime"
        );
        outboxService.record(
            "post_match_pending_change.created",
            "post_match_pending_change",
            pendingChangeId.toString(),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            request.playerId(),
            "post_match_pending_change.created",
            "post_match_pending_change",
            pendingChangeId.toString(),
            1,
            payload,
            now
        );
    }

    private String weaponPresetAggregateId(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return playerId + ":" + classTag + ":" + presetSlot + ":" + catalogVersion;
    }

    private void validateIdempotencyKey(String idempotencyKey, RuntimePresetChangeRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required"
            );
        }
        if (!idempotencyKey.equalsIgnoreCase(request.operationId().toString())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_OPERATION_ID_MISMATCH",
                "Idempotency-Key must equal body.operation_id"
            );
        }
    }

    private void validatePayload(RuntimePresetChangePayload payload) {
        if (payload.schemaVersion() != 1) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Only RuntimePresetChangePayload schema_version=1 is supported"
            );
        }
    }

    /**
     * Возвращает результат уже записанной операции, если operation_id повторили с тем же request hash.
     */
    private RuntimePresetChangeResponse replayExistingOperation(
        RuntimePresetChangeRequest request,
        String requestHash,
        ExistingOperation existing
    ) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                "Runtime operation id was reused with a different request body"
            );
        }
        return new RuntimePresetChangeResponse(
            request.operationId(),
            existing.status(),
            existing.resultRevision(),
            existing.pendingChangeId(),
            true,
            "conflict".equals(existing.status()) ? "PRESET_REVISION_CONFLICT" : null
        );
    }

    private ExistingOperation existingOperation(UUID operationId) {
        List<ExistingOperation> operations = jdbcTemplate.query(
            """
                SELECT status, result_revision, pending_change_id, request_hash
                FROM runtime_preset_change_operations
                WHERE operation_id = ?
                """,
            (rs, rowNum) -> new ExistingOperation(
                rs.getString("status"),
                rs.getObject("result_revision", Long.class),
                rs.getObject("pending_change_id", UUID.class),
                rs.getString("request_hash")
            ),
            operationId
        );
        return operations.isEmpty() ? null : operations.getFirst();
    }

    /**
     * Блокирует stream-строку для (match_id, player_id), чтобы сериализовать операции.
     */
    private void lockOperationStream(RuntimePresetChangeRequest request) {
        jdbcTemplate.update(
            """
                INSERT INTO runtime_operation_streams (match_id, player_id, last_applied_seq)
                VALUES (?, ?, 0)
                ON CONFLICT (match_id, player_id) DO NOTHING
                """,
            request.matchId(),
            request.playerId()
        );
    }

    /**
     * Проверяет, что operation_seq строго равен last_applied_seq + 1.
     */
    private void ensureOperationOrdering(RuntimePresetChangeRequest request) {
        Long lastAppliedSeq = jdbcTemplate.queryForObject(
            """
                SELECT last_applied_seq
                FROM runtime_operation_streams
                WHERE match_id = ?
                  AND player_id = ?
                FOR UPDATE
                """,
            Long.class,
            request.matchId(),
            request.playerId()
        );
        if (lastAppliedSeq == null) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RUNTIME_OPERATION_STREAM_NOT_FOUND",
                "Runtime operation stream row disappeared after insert"
            );
        }
        if (request.operationSeq() != lastAppliedSeq + 1) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUNTIME_OPERATION_SEQ_OUT_OF_ORDER",
                "Expected seq " + (lastAppliedSeq + 1) + " but got " + request.operationSeq()
            );
        }
    }

    /**
     * Продвигает last_applied_seq после успешного применения или conflict.
     */
    private void updateOperationStream(RuntimePresetChangeRequest request) {
        jdbcTemplate.update(
            """
                UPDATE runtime_operation_streams
                SET last_applied_seq = ?
                WHERE match_id = ?
                  AND player_id = ?
                """,
            request.operationSeq(),
            request.matchId(),
            request.playerId()
        );
    }

    /**
     * Блокирует weapon preset до конца транзакции, чтобы ревизия и запись операции были согласованы.
     */
    private PresetHeader lockWeaponPreset(RuntimePresetChangeRequest request) {
        List<PresetHeader> presets = jdbcTemplate.query(
            """
                SELECT catalog_version, revision
                FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                FOR UPDATE
                """,
            (rs, rowNum) -> new PresetHeader(
                rs.getLong("catalog_version"),
                rs.getLong("revision")
            ),
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot()
        );
        if (presets.isEmpty()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WEAPON_PRESET_NOT_FOUND",
                "Weapon preset was not found"
            );
        }
        return presets.getFirst();
    }

    /**
     * Создает pending change для ручного или автоматического post-match resolution.
     */
    private UUID createPendingChange(RuntimePresetChangeRequest request, long currentRevision, OffsetDateTime now) {
        UUID changeId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                INSERT INTO post_match_pending_changes(
                  change_id,
                  player_id,
                  match_id,
                  class_tag,
                  weapon_preset_slot,
                  base_weapon_preset_revision,
                  current_conflicting_revision,
                  reason_code,
                  status,
                  payload,
                  payload_schema_version,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'revision_conflict', 'pending', ?::jsonb, 1, ?, ?)
                """,
            changeId,
            request.playerId(),
            request.matchId(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.baseWeaponPresetRevision(),
            currentRevision,
            pendingPayload(request, currentRevision),
            now,
            now.plusDays(PENDING_TTL_DAYS)
        );
        return changeId;
    }

    private String pendingPayload(RuntimePresetChangeRequest request, long currentRevision) {
        Map<String, Object> payload = Map.of(
            "schema_version", 1,
            "runtime_change_payload", request.runtimeChangePayload(),
            "conflict", Map.of(
                "reason_code", "revision_conflict",
                "base_weapon_preset_revision", request.baseWeaponPresetRevision(),
                "current_weapon_preset_revision", currentRevision
            ),
            "resolution_options", List.of("apply_if_still_valid", "discard", "manual_merge")
        );
        return toJson(payload);
    }

    /**
     * Пытается вставить operation с 'processing' статусом.
     * Если operation_id уже существует — возвращает ExistingOperation для replay.
     * Если вставка успешна — возвращает null (вызывающий продолжит apply).
     */
    private int tryInsertOperation(
        RuntimePresetChangeRequest request,
        String requestHash,
        OffsetDateTime now
    ) {
        return jdbcTemplate.update(
            """
                INSERT INTO runtime_preset_change_operations(
                  operation_id, match_id, player_id, operation_seq,
                  class_tag, weapon_preset_slot, base_weapon_preset_revision,
                  status, result_revision, pending_change_id,
                  request_hash, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'processing', NULL, NULL, ?, ?)
                ON CONFLICT (operation_id) DO NOTHING
                """,
            request.operationId(),
            request.matchId(),
            request.playerId(),
            request.operationSeq(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.baseWeaponPresetRevision(),
            requestHash,
            now
        );
    }

    /**
     * Обновляет статус уже вставленной операции (processing -> applied/conflict/rejected).
     */
    private void updateOperationStatus(
        UUID operationId,
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        OffsetDateTime now
    ) {
        jdbcTemplate.update(
            """
                UPDATE runtime_preset_change_operations
                SET status = ?,
                    result_revision = ?,
                    pending_change_id = ?,
                    updated_at = ?
                WHERE operation_id = ?
                """,
            status, resultRevision, pendingChangeId, now, operationId
        );
    }

    private String requestHash(RuntimePresetChangeRequest request) {
        return sha256(toJson(request));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RUNTIME_CHANGE_SERIALIZATION_FAILED",
                "Unable to serialize runtime preset change"
            );
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "REQUEST_HASH_FAILED",
                "Unable to hash runtime preset change request"
            );
        }
    }

    private record PresetHeader(long catalogVersion, long revision) {
    }

    private record ExistingOperation(
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash
    ) {
    }
}
