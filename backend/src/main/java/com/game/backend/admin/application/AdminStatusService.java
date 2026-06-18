package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.game.backend.common.api.ApiException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
    private final AdminRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final OffsetDateTime startedAt;

    public AdminStatusService(AdminRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.startedAt = OffsetDateTime.now();
    }

    /**
     * Компактный обзор health/counts, который dashboard обновляет периодически.
     */
    public Map<String, Object> overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("backend", Map.of(
            "ok", true,
            "uptime", formatDuration(Duration.between(startedAt, OffsetDateTime.now()))
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
        return repository.listServers();
    }

    public List<Map<String, Object>> matches() {
        return repository.listMatches();
    }

    public List<Map<String, Object>> recentAudit() {
        return repository.listRecentAuditEvents();
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

        return repository.searchPlayersByLogin(trimmed);
    }

    public Map<String, Object> weaponAccess(UUID playerId, String weaponId, long catalogVersion) {
        List<Map<String, Object>> rows = repository.findWeaponAccess(playerId, weaponId, catalogVersion);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_ITEM_NOT_FOUND", "Weapon access state was not found");
        }
        return rows.getFirst();
    }

    public List<Map<String, Object>> weaponAccessAudit(UUID playerId, String weaponId, long catalogVersion) {
        return repository.listWeaponAccessAudit(playerId, weaponId, catalogVersion);
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
        response.put("oldestPendingAge", oldest == null ? "0s" : formatDuration(Duration.between(oldest, OffsetDateTime.now())));
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
}
