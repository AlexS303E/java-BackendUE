package com.game.backend.access.api;

import com.game.backend.auth.application.CurrentPlayer;
import com.game.backend.access.application.AccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player API для получения текущей проекции доступных предметов.
 */
@RestController
public class AccessController {
    private final AccessService accessService;

    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * Возвращает access projection для авторизованного игрока, realm и версии каталога.
     */
    @GetMapping("/me/access")
    AccessResponse getMyAccess(
            Authentication authentication,
            @RequestParam(value = "realm_id", defaultValue = "global") String realmId,
            @RequestParam(value = "catalog_version", required = false) Long catalogVersion
    ) {
        return accessService.getAccess(CurrentPlayer.require(authentication).playerId(), realmId, catalogVersion);
    }
}
