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
        return new AdminOverview(
            new AdminBackendOverview(true, formatDuration(Duration.between(startedAt, now))),
            new AdminInfrastructureOverview(databaseOk(), redisOk()),
            repository.activeCatalog()
                .map(AdminCatalogOverview::from)
                .orElse(null),
            new AdminRuntimeOverview(
                repository.countRunningMatches(),
                repository.countPendingRuntimeConflicts()
            ),
            outboxOverview()
        );
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

    private AdminOutboxOverview outboxOverview() {
        Map<String, Long> counts = repository.outboxStatusCounts();
        OffsetDateTime oldest = repository.oldestPendingOutboxCreatedAt();

        return new AdminOutboxOverview(
            counts.get("pending"),
            counts.get("failed"),
            counts.get("processed"),
            oldest == null ? "0s" : formatDuration(Duration.between(oldest, now()))
        );
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

    public record AdminOverview(
        AdminBackendOverview backend,
        AdminInfrastructureOverview infrastructure,
        AdminCatalogOverview catalog,
        AdminRuntimeOverview runtime,
        AdminOutboxOverview outbox
    ) {
    }

    public record AdminBackendOverview(boolean ok, String uptime) {
    }

    public record AdminInfrastructureOverview(boolean databaseOk, boolean redisOk) {
    }

    public record AdminCatalogOverview(
        long activeVersion,
        String deploymentState,
        boolean allowNewMatches,
        boolean allowExistingMatches,
        OffsetDateTime activatedAt
    ) {
        private static AdminCatalogOverview from(AdminRepository.ActiveCatalogRow row) {
            return new AdminCatalogOverview(
                row.activeVersion(),
                row.deploymentState(),
                row.allowNewMatches(),
                row.allowExistingMatches(),
                row.activatedAt()
            );
        }
    }

    public record AdminRuntimeOverview(long runningMatches, long runtimeConflicts) {
    }

    public record AdminOutboxOverview(long pending, long failed, long processed, String oldestPendingAge) {
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

    }
}
