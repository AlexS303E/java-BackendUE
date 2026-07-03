package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

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
        List<RuntimeChangesRepository.RuntimeOperationRecord> operations = repository.findOperation(operationId);
        if (operations.isEmpty()) {
            return null;
        }
        RuntimeChangesRepository.RuntimeOperationRecord operation = operations.getFirst();
        return new ExistingOperation(
            operation.status(),
            operation.resultRevision(),
            operation.pendingChangeId(),
            operation.requestHash()
        );
    }

    public int insertProcessing(
        RuntimePresetChangeCommand command,
        String requestHash,
        OffsetDateTime now
    ) {
        return repository.insertProcessingOperation(
            command.operationId(),
            command.matchId(),
            command.playerId(),
            command.operationSeq(),
            command.classTag(),
            command.weaponPresetSlot(),
            command.baseWeaponPresetRevision(),
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
        repository.updateOperationStatus(operationId, status, resultRevision, pendingChangeId, now);
    }

    public record ExistingOperation(
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash
    ) {
    }
}
