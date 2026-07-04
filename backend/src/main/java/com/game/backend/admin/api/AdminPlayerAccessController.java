package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminPlayerAccessService;
import com.game.backend.admin.application.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin API для ручного изменения доступа игрока к предметам каталога.
 */
@RestController
public class AdminPlayerAccessController {
    private final AdminPlayerAccessService adminPlayerAccessService;

    public AdminPlayerAccessController(AdminPlayerAccessService adminPlayerAccessService) {
        this.adminPlayerAccessService = adminPlayerAccessService;
    }

    /**
     * Применяет admin override к одному item и обновляет access projection игрока.
     */
    @PostMapping("/admin/players/{player_id}/access/items/{item_id}")
    AdminItemAccessUpdateResponse updateItemAccess(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable("player_id") UUID playerId,
            @PathVariable("item_id") String itemId,
            @Valid @RequestBody AdminItemAccessUpdateRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminPlayerAccessService.updateItemAccess(
                CurrentAdmin.require(authentication),
                idempotencyKey,
                playerId,
                itemId,
                AdminItemAccessApiMapper.toCommand(request)
        ));
    }
}
