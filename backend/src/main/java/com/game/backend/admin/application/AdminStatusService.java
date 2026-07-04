package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.game.backend.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Собирает read-модель для admin dashboard из operational таблиц backend.
 */
@Service
public class AdminStatusService {
    private static final Duration CERTIFICATE_EXPIRY_WARNING_WINDOW = Duration.ofDays(14);
    private static final Duration DEFAULT_OVERVIEW_SNAPSHOT_TTL = Duration.ofSeconds(5);
    private static final int DASHBOARD_LIST_LIMIT = 50;
    private static final int PLAYER_SEARCH_LIMIT = 20;

    private final AdminRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Duration overviewSnapshotTtl;
    private final OffsetDateTime startedAt;
    private volatile OverviewSnapshot overviewSnapshot;

    @Autowired
    public AdminStatusService(AdminRepository repository, StringRedisTemplate redisTemplate) {
        this(repository, redisTemplate, Clock.systemDefaultZone(), DEFAULT_OVERVIEW_SNAPSHOT_TTL);
    }

    public AdminStatusService(AdminRepository repository, StringRedisTemplate redisTemplate, Clock clock, Duration overviewSnapshotTtl) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.overviewSnapshotTtl = overviewSnapshotTtl;
        this.startedAt = now();
    }

    /**
     * Компактный обзор health/counts, который dashboard обновляет периодически.
     */
    public AdminOverview overview() {
        OffsetDateTime now = now();
        OverviewSnapshot snapshot = overviewSnapshot;
        if (snapshot != null && snapshot.expiresAt().isAfter(now)) {
            return snapshot.response();
        }
        synchronized (this) {
            snapshot = overviewSnapshot;
            now = now();
            if (snapshot != null && snapshot.expiresAt().isAfter(now)) {
                return snapshot.response();
            }
            AdminOverview response = buildOverview(now);
            overviewSnapshot = new OverviewSnapshot(response, now.plus(overviewSnapshotTtl));
            return response;
        }
    }

    private AdminOverview buildOverview(OffsetDateTime now) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("backend", Map.of(
            "ok", true,
            "uptime", formatDuration(Duration.between(startedAt, now))
        ));
        response.put("infrastructure", Map.of(
            "databaseOk", databaseOk(),
            "redisOk", redisOk()
        ));
        response.put("catalog", repository.activeCatalog()
            .map(AdminStatusService::activeCatalogResponse)
            .orElseGet(Map::of));
        response.put("runtime", Map.of(
            "runningMatches", repository.countRunningMatches(),
            "runtimeConflicts", repository.countPendingRuntimeConflicts()
        ));
        response.put("outbox", outboxOverview());
        return new AdminOverview(response);
    }

    public List<AdminServerStatus> servers() {
        OffsetDateTime now = OffsetDateTime.now();
        return repository.listServers(DASHBOARD_LIST_LIMIT)
            .stream()
            .map(row -> serverStatusRow(row, now))
            .toList();
    }

    public List<AdminMatchStatus> matches() {
        return repository.listMatches(DASHBOARD_LIST_LIMIT)
            .stream()
            .map(AdminMatchStatus::from)
            .toList();
    }

    public List<AdminAuditStatusEvent> recentAudit() {
        return repository.listRecentAuditEvents(DASHBOARD_LIST_LIMIT)
            .stream()
            .map(AdminAuditStatusEvent::from)
            .toList();
    }

    public List<AdminPlayerSearchResult> searchPlayers(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        UUID playerId = parseUuid(trimmed);
        if (playerId != null) {
            return repository.findPlayer(playerId)
                .stream()
                .map(AdminPlayerSearchResult::from)
                .toList();
        }

        return repository.searchPlayersByLogin(trimmed, PLAYER_SEARCH_LIMIT)
            .stream()
            .map(AdminPlayerSearchResult::from)
            .toList();
    }

    public AdminWeaponAccessStatus weaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        List<AdminRepository.WeaponAccessStatusRow> rows = repository.findWeaponAccess(playerId, weaponId, catalogVersion);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_ITEM_NOT_FOUND", "Weapon access state was not found");
        }
        return AdminWeaponAccessStatus.from(rows.getFirst());
    }

    public List<AdminWeaponAccessAuditEvent> weaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return repository.listWeaponAccessAudit(playerId, weaponId, catalogVersion, DASHBOARD_LIST_LIMIT)
            .stream()
            .map(AdminWeaponAccessAuditEvent::from)
            .toList();
    }

    private boolean databaseOk() {
        try {
            return repository.databasePingOk();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean redisOk() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RedisConnectionFailureException exception) {
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Map<String, Object> outboxOverview() {
        Map<String, Long> counts = repository.outboxStatusCounts();
        OffsetDateTime oldest = repository.oldestPendingOutboxCreatedAt();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pending", counts.get("pending"));
        response.put("failed", counts.get("failed"));
        response.put("processed", counts.get("processed"));
        response.put("oldestPendingAge", oldest == null ? "0s" : formatDuration(Duration.between(oldest, now())));
        return response;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }

    private AdminServerStatus serverStatusRow(AdminRepository.ServerStatusRow source, OffsetDateTime now) {
        OffsetDateTime expiresAt = source.expiresAt();
        OffsetDateTime revokedAt = source.revokedAt();
        String status = source.status();

        boolean revoked = revokedAt != null || "revoked".equals(status);
        boolean expired = expiresAt == null || !expiresAt.isAfter(now) || "expired".equals(status);
        boolean expiresSoon = !expired && Duration.between(now, expiresAt).compareTo(CERTIFICATE_EXPIRY_WARNING_WINDOW) <= 0;

        return new AdminServerStatus(
            source.serverId(),
            source.realmId(),
            source.serverBuildId(),
            status,
            source.allowedScopes(),
            source.createdAt(),
            expiresAt,
            revokedAt,
            revoked,
            expired,
            expiresSoon,
            effectiveAuthState(status, revoked, expired, expiresSoon)
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private record OverviewSnapshot(AdminOverview response, OffsetDateTime expiresAt) {
    }

    private String effectiveAuthState(String status, boolean revoked, boolean expired, boolean expiresSoon) {
        if (revoked) {
            return "revoked";
        }
        if (expired) {
            return "expired";
        }
        if (!"active".equals(status)) {
            return "inactive";
        }
        return expiresSoon ? "expiring_soon" : "active";
    }

    public record AdminOverview(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
        }
    }

    public record AdminServerStatus(
        UUID serverId,
        String realmId,
        String serverBuildId,
        String status,
        List<String> allowedScopes,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        boolean revoked,
        boolean certificateExpired,
        boolean certificateExpiresSoon,
        String effectiveAuthState
    ) {
        public Map<String, Object> asResponse() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", serverId);
            row.put("realmId", realmId);
            row.put("serverBuildId", serverBuildId);
            row.put("status", status);
            row.put("allowedScopes", allowedScopes);
            row.put("createdAt", createdAt);
            row.put("expiresAt", expiresAt);
            row.put("revokedAt", revokedAt);
            row.put("revoked", revoked);
            row.put("certificateExpired", certificateExpired);
            row.put("certificateExpiresSoon", certificateExpiresSoon);
            row.put("effectiveAuthState", effectiveAuthState);
            return row;
        }
    }

    public record AdminMatchStatus(
        UUID matchId,
        UUID serverId,
        String realmId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
    ) {
        private static AdminMatchStatus from(AdminRepository.MatchStatusRow row) {
            return new AdminMatchStatus(
                row.matchId(),
                row.serverId(),
                row.realmId(),
                row.status(),
                row.createdAt(),
                row.finishedAt()
            );
        }

        public Map<String, Object> asResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("matchId", matchId);
            response.put("serverId", serverId);
            response.put("realmId", realmId);
            response.put("status", status);
            response.put("createdAt", createdAt);
            response.put("finishedAt", finishedAt);
            return response;
        }
    }

    public record AdminAuditStatusEvent(
        UUID eventId,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String result,
        OffsetDateTime createdAt
    ) {
        private static AdminAuditStatusEvent from(AdminRepository.RecentAuditEventRow row) {
            return new AdminAuditStatusEvent(
                row.eventId(),
                row.actorId(),
                row.action(),
                row.targetType(),
                row.targetId(),
                row.result(),
                row.createdAt()
            );
        }

        public Map<String, Object> asResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("eventId", eventId);
            response.put("actorId", actorId);
            response.put("action", action);
            response.put("targetType", targetType);
            response.put("targetId", targetId);
            response.put("result", result);
            response.put("createdAt", createdAt);
            return response;
        }
    }

    public record AdminPlayerSearchResult(
        UUID playerId,
        String loginName,
        String status,
        Long accessRevision
    ) {
        private static AdminPlayerSearchResult from(AdminRepository.PlayerSearchRow row) {
            return new AdminPlayerSearchResult(
                row.playerId(),
                row.loginName(),
                row.status(),
                row.accessRevision()
            );
        }

        public Map<String, Object> asResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("playerId", playerId);
            response.put("loginName", loginName);
            response.put("status", status);
            response.put("accessRevision", accessRevision);
            return response;
        }
    }

    public record AdminWeaponAccessAuditEvent(
        UUID ledgerEventId,
        String eventType,
        String action,
        String sourceType,
        String sourceRef,
        String actorType,
        String actorId,
        String result,
        String payload,
        OffsetDateTime createdAt
    ) {
        private static AdminWeaponAccessAuditEvent from(AdminRepository.WeaponAccessAuditRow row) {
            return new AdminWeaponAccessAuditEvent(
                row.ledgerEventId(),
                row.eventType(),
                row.action(),
                row.sourceType(),
                row.sourceRef(),
                row.actorType(),
                row.actorId(),
                row.result(),
                row.payload(),
                row.createdAt()
            );
        }

        public Map<String, Object> asResponse() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ledgerEventId", ledgerEventId);
            response.put("eventType", eventType);
            response.put("action", action);
            response.put("sourceType", sourceType);
            response.put("sourceRef", sourceRef);
            response.put("actorType", actorType);
            response.put("actorId", actorId);
            response.put("result", result);
            response.put("payload", payload);
            response.put("createdAt", createdAt);
            return response;
        }
    }

    public record AdminWeaponAccessStatus(
        String itemId,
        long catalogVersion,
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        OffsetDateTime updatedAt,
        long accessRevision,
        boolean catalogEnabled,
        boolean playerCanUse,
        boolean effectiveCanUse
    ) {
        private static AdminWeaponAccessStatus from(AdminRepository.WeaponAccessStatusRow row) {
            boolean playerCanUse = !row.hidden() && !row.lockedInShop() && !row.lockedByQuest() && !row.disabled();
            return new AdminWeaponAccessStatus(
                row.itemId(),
                row.catalogVersion(),
                row.hidden(),
                row.lockedInShop(),
                row.lockedByQuest(),
                row.disabled(),
                row.disabledReason(),
                row.unlockHintCode(),
                row.updatedAt(),
                row.accessRevision(),
                row.catalogEnabled(),
                playerCanUse,
                row.catalogEnabled() && playerCanUse
            );
        }

        public Map<String, Object> asResponse() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", itemId);
            row.put("catalogVersion", catalogVersion);
            row.put("isHidden", hidden);
            row.put("isLockedInShop", lockedInShop);
            row.put("isLockedByQuest", lockedByQuest);
            row.put("isDisabled", disabled);
            row.put("disabledReason", disabledReason);
            row.put("unlockHintCode", unlockHintCode);
            row.put("updatedAt", updatedAt);
            row.put("accessRevision", accessRevision);
            row.put("catalogEnabled", catalogEnabled);
            row.put("playerCanUse", playerCanUse);
            row.put("effectiveCanUse", effectiveCanUse);
            return row;
        }
    }

    private static Map<String, Object> activeCatalogResponse(AdminRepository.ActiveCatalogRow row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeVersion", row.activeVersion());
        response.put("deploymentState", row.deploymentState());
        response.put("allowNewMatches", row.allowNewMatches());
        response.put("allowExistingMatches", row.allowExistingMatches());
        response.put("activatedAt", row.activatedAt());
        return response;
    }
}
