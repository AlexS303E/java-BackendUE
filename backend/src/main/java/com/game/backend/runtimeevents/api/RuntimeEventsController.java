package com.game.backend.runtimeevents.api;

import com.game.backend.runtimeevents.application.RuntimeEventsService;
import com.game.backend.serverauth.application.CurrentServer;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        @Valid @RequestBody RuntimeEventRequest request
    ) {
        return runtimeEventsService.record(CurrentServer.require(authentication), request);
    }
}
