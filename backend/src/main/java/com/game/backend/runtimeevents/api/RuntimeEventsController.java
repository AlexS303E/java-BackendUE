package com.game.backend.runtimeevents.api;

import com.game.backend.runtimeevents.application.RuntimeEventCommand;
import com.game.backend.runtimeevents.application.RuntimeEventResult;
import com.game.backend.runtimeevents.application.RuntimeEventsService;
import com.game.backend.serverauth.application.CurrentServer;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server API для приема runtime facts от Dedicated Server.
 */
@RestController
public class RuntimeEventsController {
    private final RuntimeEventsService runtimeEventsService;

    public RuntimeEventsController(RuntimeEventsService runtimeEventsService) {
        this.runtimeEventsService = runtimeEventsService;
    }

    /**
     * Записывает runtime event, если DS владеет match_id и имеет scope runtime_event:write.
     */
    @PostMapping("/server/runtime-events")
    RuntimeEventResponse recordRuntimeEvent(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody RuntimeEventRequest request
    ) {
        RuntimeEventCommand command = new RuntimeEventCommand(
            request.eventId(),
            request.eventSeq(),
            request.matchId(),
            request.eventType(),
            request.playerId(),
            request.payloadSchemaVersion(),
            request.occurredAt(),
            request.payload()
        );
        return toResponse(runtimeEventsService.record(CurrentServer.require(authentication), idempotencyKey, command));
    }

    private RuntimeEventResponse toResponse(RuntimeEventResult result) {
        return new RuntimeEventResponse(
            result.eventId(),
            result.status(),
            result.duplicate()
        );
    }
}
