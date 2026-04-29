package com.game.backend.runtimechanges.api;

import com.game.backend.runtimechanges.application.RuntimePresetChangeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimePresetChangeController {
    private final RuntimePresetChangeService runtimePresetChangeService;

    public RuntimePresetChangeController(RuntimePresetChangeService runtimePresetChangeService) {
        this.runtimePresetChangeService = runtimePresetChangeService;
    }

    @PostMapping("/server/runtime-preset-changes")
    ResponseEntity<?> submitRuntimePresetChanges(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody RuntimePresetChangeRequest request
    ) {
        RuntimePresetChangeResponse response = runtimePresetChangeService.submit(idempotencyKey, request);
        if ("conflict".equals(response.status())) {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Runtime preset change conflicts with a newer durable preset revision"
            );
            detail.setTitle("PRESET_REVISION_CONFLICT");
            detail.setProperty("code", "PRESET_REVISION_CONFLICT");
            detail.setProperty("operation_id", response.operationId());
            detail.setProperty("pending_change_id", response.pendingChangeId());
            detail.setProperty("duplicate", response.duplicate());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
        }
        return ResponseEntity.ok(response);
    }
}
