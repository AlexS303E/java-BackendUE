package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.game.backend.common.api.ApiException;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RuntimeOperationStreamService {
    private final RuntimeChangesRepository repository;

    public RuntimeOperationStreamService(RuntimeChangesRepository repository) {
        this.repository = repository;
    }

    public void lockAndValidateNextSequence(RuntimePresetChangeRequest request) {
        repository.ensureOperationStream(request.matchId(), request.playerId());

        Long lastAppliedSeq = repository.lockOperationStream(request.matchId(), request.playerId());
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

    public void advance(RuntimePresetChangeRequest request) {
        repository.advanceOperationStream(request.matchId(), request.playerId(), request.operationSeq());
    }
}
