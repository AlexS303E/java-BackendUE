package com.game.backend.runtimechanges.api;

import com.game.backend.runtimechanges.application.RuntimePresetChangeService;
import com.game.backend.runtimechanges.application.RuntimePresetChangeCommand;
import com.game.backend.runtimechanges.application.RuntimePresetChangeResult;
import com.game.backend.serverauth.application.CurrentServer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server API для фиксации runtime-изменений loadout, сделанных прямо во время матча.
 */
@RestController
public class RuntimePresetChangeController {
    private final RuntimePresetChangeService runtimePresetChangeService;

    public RuntimePresetChangeController(RuntimePresetChangeService runtimePresetChangeService) {
        this.runtimePresetChangeService = runtimePresetChangeService;
    }

    /**
     * Принимает идемпотентную операцию от DS и возвращает applied либо conflict.
     */
    @PostMapping("/server/runtime-preset-changes")
    ResponseEntity<?> submitRuntimePresetChanges(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody RuntimePresetChangeRequest request
    ) {
        RuntimePresetChangeCommand command = new RuntimePresetChangeCommand(
            request.operationId(),
            request.operationSeq(),
            request.matchId(),
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.baseWeaponPresetRevision(),
            request.runtimeChangePayload()
        );
        RuntimePresetChangeResult result = runtimePresetChangeService.submit(
            CurrentServer.require(authentication),
            idempotencyKey,
            command
        );
        RuntimePresetChangeResponse response = toResponse(result);
        if ("conflict".equals(result.status())) {
            // Конфликт ревизии отдаем как problem+json, но сохраняем pending change для post-match решения.
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Runtime preset change conflicts with a newer durable preset revision"
            );
            detail.setTitle("PRESET_REVISION_CONFLICT");
            detail.setProperty("code", "PRESET_REVISION_CONFLICT");
            detail.setProperty("operation_id", result.operationId());
            detail.setProperty("pending_change_id", result.pendingChangeId());
            detail.setProperty("duplicate", result.duplicate());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
        }
        return ResponseEntity.ok(response);
    }

    private RuntimePresetChangeResponse toResponse(RuntimePresetChangeResult result) {
        return new RuntimePresetChangeResponse(
            result.operationId(),
            result.status(),
            result.resultRevision(),
            result.pendingChangeId(),
            result.duplicate(),
            result.errorCode()
        );
    }
}
