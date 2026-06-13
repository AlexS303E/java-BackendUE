package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RuntimeOperationRecorder {
    private final RuntimeChangesRepository repository;

    public RuntimeOperationRecorder(RuntimeChangesRepository repository) {
        this.repository = repository;
    }

    public ExistingOperation find(UUID operationId) {
        List<ExistingOperation> operations = repository.query(
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

    public int insertProcessing(
        RuntimePresetChangeRequest request,
        String requestHash,
        OffsetDateTime now
    ) {
        return repository.update(
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

    public void markApplied(UUID operationId, long resultRevision, OffsetDateTime now) {
        updateStatus(operationId, "applied", resultRevision, null, now);
    }

    public void markConflict(UUID operationId, UUID pendingChangeId, OffsetDateTime now) {
        updateStatus(operationId, "conflict", null, pendingChangeId, now);
    }

    public void markRejected(UUID operationId, OffsetDateTime now) {
        updateStatus(operationId, "rejected", null, null, now);
    }

    public void markFailed(UUID operationId, OffsetDateTime now) {
        updateStatus(operationId, "failed", null, null, now);
    }

    private void updateStatus(
        UUID operationId,
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        OffsetDateTime now
    ) {
        repository.update(
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

    public record ExistingOperation(
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash
    ) {
    }
}
