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
        repository.update(
            """
                INSERT INTO runtime_operation_streams (match_id, player_id, last_applied_seq)
                VALUES (?, ?, 0)
                ON CONFLICT (match_id, player_id) DO NOTHING
                """,
            request.matchId(),
            request.playerId()
        );

        Long lastAppliedSeq = repository.queryForObject(
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

    public void advance(RuntimePresetChangeRequest request) {
        repository.update(
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
}
