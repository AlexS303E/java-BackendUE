package com.game.backend.access.api;

import com.game.backend.access.application.AccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class AccessController {
    private final AccessService accessService;

    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping("/me/access")
    AccessResponse getMyAccess(
        @RequestHeader("X-Player-Id") UUID playerId,
        @RequestParam(defaultValue = "global") String realmId,
        @RequestParam(required = false) Long catalogVersion
    ) {
        return accessService.getAccess(playerId, realmId, catalogVersion);
    }
}
