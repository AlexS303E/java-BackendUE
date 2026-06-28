package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.game.backend.common.api.ApiException;
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
    public Map<String, Object> overview() {
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
            Map<String, Object> response = buildOverview(now);
            overviewSnapshot = new OverviewSnapshot(response, now.plus(overviewSnapshotTtl));
            return response;
        }
    }

    private Map<String, Object> buildOverview(OffsetDateTime now) {
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
        return response;
    }

    public List<Map<String, Object>> servers() {
        OffsetDateTime now = OffsetDateTime.now();
        return repository.listServers(DASHBOARD_LIST_LIMIT)
            .stream()
            .map(row -> serverStatusRow(row, now))
            .toList();
    }

    public List<Map<String, Object>> matches() {
        return repository.listMatches(DASHBOARD_LIST_LIMIT);
    }

    public List<Map<String, Object>> recentAudit() {
        return repository.listRecentAuditEvents(DASHBOARD_LIST_LIMIT);
    }

    public List<Map<String, Object>> searchPlayers(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        UUID playerId = parseUuid(trimmed);
        if (playerId != null) {
            return repository.findPlayer(playerId);
        }

        return repository.searchPlayersByLogin(trimmed, PLAYER_SEARCH_LIMIT);
    }

    public Map<String, Object> weaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        List<Map<String, Object>> rows = repository.findWeaponAccess(playerId, weaponId, catalogVersion);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_ITEM_NOT_FOUND", "Weapon access state was not found");
        }
        return rows.getFirst();
    }

    public List<Map<String, Object>> weaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return repository.listWeaponAccessAudit(playerId, weaponId, catalogVersion, DASHBOARD_LIST_LIMIT);
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

    private Map<String, Object> serverStatusRow(Map<String, Object> source, OffsetDateTime now) {
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
        return row;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private record OverviewSnapshot(Map<String, Object> response, OffsetDateTime expiresAt) {
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
}
