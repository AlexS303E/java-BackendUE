package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RuntimeOperationStreamService {
    private final RuntimeChangesRepository repository;

    public RuntimeOperationStreamService(RuntimeChangesRepository repository) {
        this.repository = repository;
    }

    public void lockAndValidateNextSequence(RuntimePresetChangeCommand command) {
        repository.ensureOperationStream(command.matchId(), command.playerId());

        Long lastAppliedSeq = repository.lockOperationStream(command.matchId(), command.playerId());
        if (lastAppliedSeq == null) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RUNTIME_OPERATION_STREAM_NOT_FOUND",
                "Runtime operation stream row disappeared after insert"
            );
        }
        if (command.operationSeq() != lastAppliedSeq + 1) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUNTIME_OPERATION_SEQ_OUT_OF_ORDER",
                "Expected seq " + (lastAppliedSeq + 1) + " but got " + command.operationSeq()
            );
        }
    }

    public void advance(RuntimePresetChangeCommand command) {
        repository.advanceOperationStream(command.matchId(), command.playerId(), command.operationSeq());
    }
}
