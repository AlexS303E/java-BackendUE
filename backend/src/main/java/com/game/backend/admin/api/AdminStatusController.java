package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminStatusService;
import com.game.backend.admin.application.CurrentAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
        return overviewResponse(adminStatusService.overview());
    }

    /**
     * Возвращает последние server identities.
     */
    @GetMapping("/admin/status/servers")
    Map<String, Object> servers(Authentication authentication) {
        CurrentAdmin.require(authentication);
        return Map.of("servers", adminStatusService.servers()
            .stream()
            .map(AdminStatusController::serverResponse)
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
            .map(AdminStatusController::matchResponse)
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
            .map(AdminStatusController::auditEventResponse)
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
            .map(AdminStatusController::playerResponse)
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
        return weaponAccessResponse(adminStatusService.weaponAccess(playerId, weaponId, catalogVersion));
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
            .map(AdminStatusController::weaponAccessAuditResponse)
            .toList());
    }

    private static Map<String, Object> overviewResponse(AdminStatusService.AdminOverview overview) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("backend", backendOverviewResponse(overview.backend()));
        response.put("infrastructure", infrastructureOverviewResponse(overview.infrastructure()));
        response.put("catalog", overview.catalog() == null ? Map.of() : catalogOverviewResponse(overview.catalog()));
        response.put("runtime", runtimeOverviewResponse(overview.runtime()));
        response.put("outbox", outboxOverviewResponse(overview.outbox()));
        return response;
    }

    private static Map<String, Object> backendOverviewResponse(AdminStatusService.AdminBackendOverview backend) {
        return Map.of(
            "ok", backend.ok(),
            "uptime", backend.uptime()
        );
    }

    private static Map<String, Object> infrastructureOverviewResponse(AdminStatusService.AdminInfrastructureOverview infrastructure) {
        return Map.of(
            "databaseOk", infrastructure.databaseOk(),
            "redisOk", infrastructure.redisOk()
        );
    }

    private static Map<String, Object> catalogOverviewResponse(AdminStatusService.AdminCatalogOverview catalog) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeVersion", catalog.activeVersion());
        response.put("deploymentState", catalog.deploymentState());
        response.put("allowNewMatches", catalog.allowNewMatches());
        response.put("allowExistingMatches", catalog.allowExistingMatches());
        response.put("activatedAt", catalog.activatedAt());
        return response;
    }

    private static Map<String, Object> runtimeOverviewResponse(AdminStatusService.AdminRuntimeOverview runtime) {
        return Map.of(
            "runningMatches", runtime.runningMatches(),
            "runtimeConflicts", runtime.runtimeConflicts()
        );
    }

    private static Map<String, Object> outboxOverviewResponse(AdminStatusService.AdminOutboxOverview outbox) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pending", outbox.pending());
        response.put("failed", outbox.failed());
        response.put("processed", outbox.processed());
        response.put("oldestPendingAge", outbox.oldestPendingAge());
        return response;
    }

    private static Map<String, Object> serverResponse(AdminStatusService.AdminServerStatus server) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverId", server.serverId());
        response.put("realmId", server.realmId());
        response.put("serverBuildId", server.serverBuildId());
        response.put("status", server.status());
        response.put("allowedScopes", server.allowedScopes());
        response.put("createdAt", server.createdAt());
        response.put("expiresAt", server.expiresAt());
        response.put("revokedAt", server.revokedAt());
        response.put("revoked", server.revoked());
        response.put("certificateExpired", server.certificateExpired());
        response.put("certificateExpiresSoon", server.certificateExpiresSoon());
        response.put("effectiveAuthState", server.effectiveAuthState());
        return response;
    }

    private static Map<String, Object> matchResponse(AdminStatusService.AdminMatchStatus match) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("matchId", match.matchId());
        response.put("serverId", match.serverId());
        response.put("realmId", match.realmId());
        response.put("status", match.status());
        response.put("createdAt", match.createdAt());
        response.put("finishedAt", match.finishedAt());
        return response;
    }

    private static Map<String, Object> auditEventResponse(AdminStatusService.AdminAuditStatusEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("eventId", event.eventId());
        response.put("actorId", event.actorId());
        response.put("action", event.action());
        response.put("targetType", event.targetType());
        response.put("targetId", event.targetId());
        response.put("result", event.result());
        response.put("createdAt", event.createdAt());
        return response;
    }

    private static Map<String, Object> playerResponse(AdminStatusService.AdminPlayerSearchResult player) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("playerId", player.playerId());
        response.put("loginName", player.loginName());
        response.put("status", player.status());
        response.put("accessRevision", player.accessRevision());
        return response;
    }

    private static Map<String, Object> weaponAccessResponse(AdminStatusService.AdminWeaponAccessStatus access) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("itemId", access.itemId());
        response.put("catalogVersion", access.catalogVersion());
        response.put("isHidden", access.hidden());
        response.put("isLockedInShop", access.lockedInShop());
        response.put("isLockedByQuest", access.lockedByQuest());
        response.put("isDisabled", access.disabled());
        response.put("disabledReason", access.disabledReason());
        response.put("unlockHintCode", access.unlockHintCode());
        response.put("updatedAt", access.updatedAt());
        response.put("accessRevision", access.accessRevision());
        response.put("catalogEnabled", access.catalogEnabled());
        response.put("playerCanUse", access.playerCanUse());
        response.put("effectiveCanUse", access.effectiveCanUse());
        return response;
    }

    private static Map<String, Object> weaponAccessAuditResponse(AdminStatusService.AdminWeaponAccessAuditEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ledgerEventId", event.ledgerEventId());
        response.put("eventType", event.eventType());
        response.put("action", event.action());
        response.put("sourceType", event.sourceType());
        response.put("sourceRef", event.sourceRef());
        response.put("actorType", event.actorType());
        response.put("actorId", event.actorId());
        response.put("result", event.result());
        response.put("payload", event.payload());
        response.put("createdAt", event.createdAt());
        return response;
    }
}
