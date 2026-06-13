package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeResponse;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
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
import java.util.UUID;

/**
 * Применяет runtime preset changes от DS и фиксирует conflict как post-match pending change.
 */
@Service
public class RuntimePresetChangeService {
    private static final String AUDIT_ACTION = "runtime_preset_change.submit";
    private static final String AUDIT_SCOPE = "runtime_preset_change:write";

    private final RuntimeChangesRepository repository;
    private final ObjectMapper objectMapper;
    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;
    private final WeaponPresetRuntimeChangeApplier runtimeChangeApplier;
    private final OutboxService outboxService;
    private final RuntimeOperationRecorder operationRecorder;
    private final RuntimeOperationStreamService operationStreamService;
    private final RuntimeChangeConflictService conflictService;

    public RuntimePresetChangeService(
        RuntimeChangesRepository repository,
        ObjectMapper objectMapper,
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService,
        WeaponPresetRuntimeChangeApplier runtimeChangeApplier,
        OutboxService outboxService,
        RuntimeOperationRecorder operationRecorder,
        RuntimeOperationStreamService operationStreamService,
        RuntimeChangeConflictService conflictService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
        this.runtimeChangeApplier = runtimeChangeApplier;
        this.outboxService = outboxService;
        this.operationRecorder = operationRecorder;
        this.operationStreamService = operationStreamService;
        this.conflictService = conflictService;
    }

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
            RuntimeOperationRecorder.ExistingOperation existing = operationRecorder.find(request.operationId());
            if (existing != null) {
                return auditedResponse(server, request, replayExistingOperation(request, requestHash, existing));
            }

            // 2. Lock stream and validate ordering
            operationStreamService.lockAndValidateNextSequence(request);

            // 3. INSERT operation with 'processing' status
            int inserted = operationRecorder.insertProcessing(request, requestHash, now);
            if (inserted == 0) {
                // Race: concurrent thread inserted between SELECT and INSERT
                existing = operationRecorder.find(request.operationId());
                return auditedResponse(server, request, replayExistingOperation(request, requestHash, existing));
            }

            // 3. Lock preset and apply
            PresetHeader preset = lockWeaponPreset(request);
            if (preset.revision() != request.baseWeaponPresetRevision()) {
                UUID pendingChangeId = conflictService.createRevisionConflict(request, preset.revision(), now);
                operationRecorder.markConflict(request.operationId(), pendingChangeId, now);
                operationStreamService.advance(request);
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
                if ("rejected".equals(opStatus)) {
                    operationRecorder.markRejected(request.operationId(), now);
                } else {
                    operationRecorder.markFailed(request.operationId(), now);
                }
                operationStreamService.advance(request);
                recordRuntimePresetFailed(request, preset.catalogVersion(), opStatus, reason, now);
                return auditedResponse(
                    server, request,
                    new RuntimePresetChangeResponse(
                        request.operationId(), opStatus, null, null, false, reason
                    )
                );
            }

            long resultRevision = preset.revision() + 1;
            repository.updateWeaponPresetRevision(
                request.playerId(),
                request.classTag(),
                request.weaponPresetSlot(),
                preset.catalogVersion(),
                resultRevision,
                now
            );

            operationRecorder.markApplied(request.operationId(), resultRevision, now);
            operationStreamService.advance(request);
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
        RuntimeOperationRecorder.ExistingOperation existing
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

    /**
     * Блокирует weapon preset до конца транзакции, чтобы ревизия и запись операции были согласованы.
     */
    private PresetHeader lockWeaponPreset(RuntimePresetChangeRequest request) {
        List<RuntimeChangesRepository.PresetHeader> presets = repository.lockWeaponPreset(
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
        RuntimeChangesRepository.PresetHeader preset = presets.getFirst();
        return new PresetHeader(preset.catalogVersion(), preset.revision());
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

}
