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
        response.put("catalog", repository.activeCatalog());
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
            .map(AdminMatchStatus::new)
            .toList();
    }

    public List<AdminAuditStatusEvent> recentAudit() {
        return repository.listRecentAuditEvents(DASHBOARD_LIST_LIMIT)
            .stream()
            .map(AdminAuditStatusEvent::new)
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
                .map(AdminPlayerSearchResult::new)
                .toList();
        }

        return repository.searchPlayersByLogin(trimmed, PLAYER_SEARCH_LIMIT)
            .stream()
            .map(AdminPlayerSearchResult::new)
            .toList();
    }

    public AdminWeaponAccessStatus weaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        List<Map<String, Object>> rows = repository.findWeaponAccess(playerId, weaponId, catalogVersion);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_ITEM_NOT_FOUND", "Weapon access state was not found");
        }
        return AdminWeaponAccessStatus.from(rows.getFirst());
    }

    public List<AdminWeaponAccessAuditEvent> weaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return repository.listWeaponAccessAudit(playerId, weaponId, catalogVersion, DASHBOARD_LIST_LIMIT)
            .stream()
            .map(AdminWeaponAccessAuditEvent::new)
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

    private AdminServerStatus serverStatusRow(Map<String, Object> source, OffsetDateTime now) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        OffsetDateTime expiresAt = (OffsetDateTime) row.get("expiresAt");
        OffsetDateTime revokedAt = (OffsetDateTime) row.get("revokedAt");
        String status = (String) row.get("status");

        boolean revoked = revokedAt != null || "revoked".equals(status);
        boolean expired = expiresAt == null || !expiresAt.isAfter(now) || "expired".equals(status);
        boolean expiresSoon = !expired && Duration.between(now, expiresAt).compareTo(CERTIFICATE_EXPIRY_WARNING_WINDOW) <= 0;

        row.put("revoked", revoked);
        row.put("certificateExpired", expired);
        row.put("certificateExpiresSoon", expiresSoon);
        row.put("effectiveAuthState", effectiveAuthState(status, revoked, expired, expiresSoon));
        return new AdminServerStatus(row);
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

    public record AdminServerStatus(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
        }
    }

    public record AdminMatchStatus(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
        }
    }

    public record AdminAuditStatusEvent(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
        }
    }

    public record AdminPlayerSearchResult(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
        }
    }

    public record AdminWeaponAccessAuditEvent(Map<String, Object> values) {
        public Map<String, Object> asResponse() {
            return values;
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
        private static AdminWeaponAccessStatus from(Map<String, Object> row) {
            return new AdminWeaponAccessStatus(
                (String) row.get("itemId"),
                ((Number) row.get("catalogVersion")).longValue(),
                Boolean.TRUE.equals(row.get("isHidden")),
                Boolean.TRUE.equals(row.get("isLockedInShop")),
                Boolean.TRUE.equals(row.get("isLockedByQuest")),
                Boolean.TRUE.equals(row.get("isDisabled")),
                (String) row.get("disabledReason"),
                (String) row.get("unlockHintCode"),
                (OffsetDateTime) row.get("updatedAt"),
                ((Number) row.get("accessRevision")).longValue(),
                Boolean.TRUE.equals(row.get("catalogEnabled")),
                Boolean.TRUE.equals(row.get("playerCanUse")),
                Boolean.TRUE.equals(row.get("effectiveCanUse"))
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
}
