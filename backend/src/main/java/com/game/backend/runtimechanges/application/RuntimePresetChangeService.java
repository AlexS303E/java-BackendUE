package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
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
    public RuntimePresetChangeResult submit(
        ServerIdentity server,
        String idempotencyKey,
        RuntimePresetChangeCommand command
    ) {
        boolean matchAssigned = false;
        try {
            validateIdempotencyKey(idempotencyKey, command);
            validatePayload(command.runtimeChangePayload());

            String requestHash = requestHash(command);
            serverMatchService.ensureAssignedForRuntimeChange(server, command);
            matchAssigned = true;
            OffsetDateTime now = OffsetDateTime.now();

            // 1. Check for existing operation (idempotency) before locking/ordering
            RuntimeOperationRecorder.ExistingOperation existing = operationRecorder.find(command.operationId());
            if (existing != null) {
                return auditedResponse(server, command, replayExistingOperation(command, requestHash, existing));
            }

            // 2. Lock stream and validate ordering
            operationStreamService.lockAndValidateNextSequence(command);

            // 3. INSERT operation with 'processing' status
            int inserted = operationRecorder.insertProcessing(command, requestHash, now);
            if (inserted == 0) {
                // Race: concurrent thread inserted between SELECT and INSERT
                existing = operationRecorder.find(command.operationId());
                return auditedResponse(server, command, replayExistingOperation(command, requestHash, existing));
            }

            // 3. Lock preset and apply
            PresetHeader preset = lockWeaponPreset(command);
            if (preset.revision() != command.baseWeaponPresetRevision()) {
                UUID pendingChangeId = conflictService.createRevisionConflict(command, preset.revision(), now);
                operationRecorder.markConflict(command.operationId(), pendingChangeId, now);
                operationStreamService.advance(command);
                return auditedResponse(
                    server, command,
                    new RuntimePresetChangeResult(
                        command.operationId(), "conflict", null, pendingChangeId, false,
                        "PRESET_REVISION_CONFLICT"
                    )
                );
            }

            try {
                runtimeChangeApplier.apply(
                    command.playerId(), command.classTag(), command.weaponPresetSlot(),
                    preset.catalogVersion(), command.runtimeChangePayload(), now
                );
            } catch (ApiException exception) {
                String opStatus = exception.status() == HttpStatus.UNPROCESSABLE_ENTITY ? "rejected" : "failed";
                String reason = exception.code();
                if ("rejected".equals(opStatus)) {
                    operationRecorder.markRejected(command.operationId(), now);
                } else {
                    operationRecorder.markFailed(command.operationId(), now);
                }
                operationStreamService.advance(command);
                recordRuntimePresetFailed(command, preset.catalogVersion(), opStatus, reason, now);
                return auditedResponse(
                    server, command,
                    new RuntimePresetChangeResult(
                        command.operationId(), opStatus, null, null, false, reason
                    )
                );
            }

            long resultRevision = preset.revision() + 1;
            repository.updateWeaponPresetRevision(
                command.playerId(),
                command.classTag(),
                command.weaponPresetSlot(),
                preset.catalogVersion(),
                resultRevision,
                now
            );

            operationRecorder.markApplied(command.operationId(), resultRevision, now);
            operationStreamService.advance(command);
            recordRuntimePresetApplied(command, preset.catalogVersion(), resultRevision, now);
            return auditedResponse(
                server, command,
                new RuntimePresetChangeResult(
                    command.operationId(), "applied", resultRevision, null, false, null
                )
            );
        } catch (ApiException exception) {
            auditFailure(server, command, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, command, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private RuntimePresetChangeResult auditedResponse(
        ServerIdentity server,
        RuntimePresetChangeCommand command,
        RuntimePresetChangeResult response
    ) {
        auditSuccess(server, command, response);
        return response;
    }

    private void auditSuccess(
        ServerIdentity server,
        RuntimePresetChangeCommand command,
        RuntimePresetChangeResult response
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("match_id", command.matchId());
        payload.put("operation_id", command.operationId());
        payload.put("operation_seq", command.operationSeq());
        payload.put("player_id", command.playerId());
        payload.put("class_tag", command.classTag());
        payload.put("weapon_preset_slot", command.weaponPresetSlot());
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
            command.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            payload
        );
    }

    private void auditFailure(
        ServerIdentity server,
        RuntimePresetChangeCommand command,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        serverAuditService.record(
            server,
            matchAssigned ? command.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            Map.of(
                "match_id", command.matchId(),
                "operation_id", command.operationId(),
                "operation_seq", command.operationSeq(),
                "player_id", command.playerId(),
                "class_tag", command.classTag(),
                "weapon_preset_slot", command.weaponPresetSlot(),
                "code", code,
                "status", status
            )
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }

    private void recordRuntimePresetApplied(
        RuntimePresetChangeCommand command,
        long catalogVersion,
        long resultRevision,
        OffsetDateTime now
    ) {
        outboxService.recordWeaponPresetRuntimeChanged(
            command.playerId(),
            command.matchId(),
            command.operationId(),
            command.classTag(),
            command.weaponPresetSlot(),
            catalogVersion,
            command.baseWeaponPresetRevision(),
            resultRevision,
            now
        );
    }

    private void recordRuntimePresetFailed(
        RuntimePresetChangeCommand command,
        long catalogVersion,
        String status,
        String reason,
        OffsetDateTime now
    ) {
        outboxService.recordWeaponPresetRuntimeFailed(
            command.playerId(),
            command.matchId(),
            command.operationId(),
            command.classTag(),
            command.weaponPresetSlot(),
            catalogVersion,
            command.baseWeaponPresetRevision(),
            status,
            reason,
            now
        );
    }

    private void validateIdempotencyKey(String idempotencyKey, RuntimePresetChangeCommand command) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required"
            );
        }
        if (!idempotencyKey.equalsIgnoreCase(command.operationId().toString())) {
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
    private RuntimePresetChangeResult replayExistingOperation(
        RuntimePresetChangeCommand command,
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
        return new RuntimePresetChangeResult(
            command.operationId(),
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
    private PresetHeader lockWeaponPreset(RuntimePresetChangeCommand command) {
        List<RuntimeChangesRepository.PresetHeader> presets = repository.lockWeaponPreset(
            command.playerId(),
            command.classTag(),
            command.weaponPresetSlot()
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

    private String requestHash(RuntimePresetChangeCommand command) {
        return sha256(toJson(command));
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
