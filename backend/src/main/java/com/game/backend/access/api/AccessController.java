package com.game.backend.access.api;

import com.game.backend.auth.application.CurrentPlayer;
import com.game.backend.access.application.AccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessController {
    private final AccessService accessService;

    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping("/me/access")
    AccessResponse getMyAccess(
        Authentication authentication,
        @RequestParam(defaultValue = "global") String realmId,
        @RequestParam(required = false) Long catalogVersion
    ) {
        return accessService.getAccess(CurrentPlayer.require(authentication).playerId(), realmId, catalogVersion);
    }
}
