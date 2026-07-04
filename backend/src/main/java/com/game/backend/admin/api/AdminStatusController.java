package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminStatusService;
import com.game.backend.admin.application.CurrentAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Read-only endpoints для admin dashboard и диагностики текущего состояния backend.
 */
@RestController
public class AdminStatusController {
    private final AdminStatusService adminStatusService;

    public AdminStatusController(AdminStatusService adminStatusService) {
        this.adminStatusService = adminStatusService;
    }

    /**
     * Возвращает компактную сводку по инфраструктуре, каталогу, матчам и outbox.
     */
    @GetMapping("/admin/status/overview")
    Map<String, Object> overview(Authentication authentication) {
        CurrentAdmin.require(authentication);
        return adminStatusService.overview().asResponse();
    }

    /**
     * Возвращает последние server identities.
     */
    @GetMapping("/admin/status/servers")
    Map<String, Object> servers(Authentication authentication) {
        CurrentAdmin.require(authentication);
        return Map.of("servers", adminStatusService.servers()
            .stream()
            .map(AdminStatusService.AdminServerStatus::asResponse)
            .toList());
    }

    /**
     * Возвращает последние матчи dedicated servers.
     */
    @GetMapping("/admin/status/matches")
    Map<String, Object> matches(Authentication authentication) {
        CurrentAdmin.require(authentication);
        return Map.of("matches", adminStatusService.matches()
            .stream()
            .map(AdminStatusService.AdminMatchStatus::asResponse)
            .toList());
    }

    /**
     * Возвращает последние admin audit события.
     */
    @GetMapping("/admin/status/recent-audit")
    Map<String, Object> recentAudit(Authentication authentication) {
        CurrentAdmin.require(authentication);
        return Map.of("events", adminStatusService.recentAudit()
            .stream()
            .map(AdminStatusService.AdminAuditStatusEvent::asResponse)
            .toList());
    }

    /**
     * Ищет игроков по UUID или части login_name.
     */
    @GetMapping("/admin/status/players/search")
    Map<String, Object> searchPlayers(
            Authentication authentication,
            @RequestParam("query") String query
    ) {
        CurrentAdmin.require(authentication);
        return Map.of("players", adminStatusService.searchPlayers(query)
            .stream()
            .map(AdminStatusService.AdminPlayerSearchResult::asResponse)
            .toList());
    }

    /**
     * Возвращает текущую projection доступа игрока к оружию.
     */
    @GetMapping("/admin/status/players/{player_id}/weapon-access")
    Map<String, Object> weaponAccess(
            Authentication authentication,
            @PathVariable("player_id") UUID playerId,
            @RequestParam("weapon_id") String weaponId,
            @RequestParam("catalog_version") long catalogVersion
    ) {
        CurrentAdmin.require(authentication);
        return adminStatusService.weaponAccess(playerId, weaponId, catalogVersion).asResponse();
    }

    /**
     * Возвращает ledger историю изменения доступа игрока к оружию.
     */
    @GetMapping("/admin/status/players/{player_id}/weapon-access/audit")
    Map<String, Object> weaponAccessAudit(
            Authentication authentication,
            @PathVariable("player_id") UUID playerId,
            @RequestParam("weapon_id") String weaponId,
            @RequestParam("catalog_version") long catalogVersion
    ) {
        CurrentAdmin.require(authentication);
        return Map.of("events", adminStatusService.weaponAccessAudit(playerId, weaponId, catalogVersion)
            .stream()
            .map(AdminStatusService.AdminWeaponAccessAuditEvent::asResponse)
            .toList());
    }
}
