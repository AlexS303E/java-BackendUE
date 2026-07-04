package com.game.backend;

import com.game.backend.admin.application.AdminStatusService;
import com.game.backend.admin.repository.AdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStatusServiceTest {
    private final AdminRepository repository = mock(AdminRepository.class);
    private final AdminStatusService service = new AdminStatusService(repository, redisDownTemplate());

    @Test
    void overviewShouldUseOnlyLightweightAggregateQueriesForPolling() {
        when(repository.databasePingOk()).thenReturn(true);
        when(repository.activeCatalog()).thenReturn(Map.of("activeVersion", 1L));
        when(repository.countRunningMatches()).thenReturn(2L);
        when(repository.countPendingRuntimeConflicts()).thenReturn(1L);
        when(repository.outboxStatusCounts()).thenReturn(Map.of(
            "pending", 3L,
            "failed", 0L,
            "processed", 7L
        ));
        when(repository.oldestPendingOutboxCreatedAt()).thenReturn(null);

        Map<String, Object> overview = service.overview().asResponse();

        assertThat(overview).containsKeys("backend", "infrastructure", "catalog", "runtime", "outbox");
        verify(repository).databasePingOk();
        verify(repository).activeCatalog();
        verify(repository).countRunningMatches();
        verify(repository).countPendingRuntimeConflicts();
        verify(repository).outboxStatusCounts();
        verify(repository).oldestPendingOutboxCreatedAt();
        verify(repository, never()).listServers(anyInt());
        verify(repository, never()).listMatches(anyInt());
        verify(repository, never()).listRecentAuditEvents(anyInt());
        verify(repository, never()).searchPlayersByLogin(anyString(), anyInt());
    }

    @Test
    void overviewShouldReuseSnapshotWithinPollingTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-28T00:00:00Z"));
        AdminStatusService cachedService = new AdminStatusService(
            repository,
            redisDownTemplate(),
            clock,
            Duration.ofSeconds(5)
        );
        when(repository.databasePingOk()).thenReturn(true);
        when(repository.activeCatalog()).thenReturn(Map.of("activeVersion", 1L));
        when(repository.countRunningMatches()).thenReturn(2L, 9L);
        when(repository.countPendingRuntimeConflicts()).thenReturn(1L);
        when(repository.outboxStatusCounts()).thenReturn(Map.of(
            "pending", 3L,
            "failed", 0L,
            "processed", 7L
        ));
        when(repository.oldestPendingOutboxCreatedAt()).thenReturn(null);

        AdminStatusService.AdminOverview first = cachedService.overview();
        AdminStatusService.AdminOverview second = cachedService.overview();
        clock.advance(Duration.ofSeconds(6));
        AdminStatusService.AdminOverview third = cachedService.overview();

        assertThat(second).isSameAs(first);
        assertThat(third).isNotSameAs(first);
        verify(repository, times(2)).countRunningMatches();
        verify(repository, times(2)).outboxStatusCounts();
    }

    @Test
    void blankPlayerSearchShouldNotHitDatabase() {
        assertThat(service.searchPlayers("  ")).isEqualTo(List.of());

        verify(repository, never()).findPlayer(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).searchPlayersByLogin(anyString(), anyInt());
    }

    @Test
    void serversShouldExposeCertificateExpiryAndRevocationState() {
        OffsetDateTime now = OffsetDateTime.now();
        when(repository.listServers(50)).thenReturn(List.of(
            serverRow("active", now.plusDays(30), null),
            serverRow("active", now.plusDays(2), null),
            serverRow("active", now.minusMinutes(1), null),
            serverRow("revoked", now.plusDays(30), now.minusMinutes(1))
        ));

        List<Map<String, Object>> servers = service.servers()
            .stream()
            .map(AdminStatusService.AdminServerStatus::asResponse)
            .toList();

        assertThat(servers).extracting(row -> row.get("effectiveAuthState"))
            .containsExactly("active", "expiring_soon", "expired", "revoked");
        assertThat(servers).extracting(row -> row.get("certificateExpired"))
            .containsExactly(false, false, true, false);
        assertThat(servers).extracting(row -> row.get("certificateExpiresSoon"))
            .containsExactly(false, true, false, false);
        assertThat(servers).extracting(row -> row.get("revoked"))
            .containsExactly(false, false, false, true);
    }

    @Test
    void dashboardListsShouldUseBoundedRepositoryLimits() {
        UUID playerId = UUID.randomUUID();
        when(repository.listServers(50)).thenReturn(List.of());
        when(repository.listMatches(50)).thenReturn(List.of());
        when(repository.listRecentAuditEvents(50)).thenReturn(List.of());
        when(repository.searchPlayersByLogin("player", 20)).thenReturn(List.of());
        when(repository.listWeaponAccessAudit(playerId, "weapon.ak12", 1L, 50)).thenReturn(List.of());

        service.servers();
        service.matches();
        service.recentAudit();
        service.searchPlayers("player");
        service.weaponAccessAudit(playerId, "weapon.ak12", 1L);

        verify(repository).listServers(50);
        verify(repository).listMatches(50);
        verify(repository).listRecentAuditEvents(50);
        verify(repository).searchPlayersByLogin(eq("player"), eq(20));
        verify(repository).listWeaponAccessAudit(eq(playerId), eq("weapon.ak12"), eq(1L), eq(50));
    }

    private StringRedisTemplate redisDownTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("redis down"));
        return redisTemplate;
    }

    private Map<String, Object> serverRow(String status, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serverId", UUID.randomUUID());
        row.put("realmId", "global");
        row.put("serverBuildId", "dev-build");
        row.put("status", status);
        row.put("allowedScopes", List.of("runtime_event:write"));
        row.put("createdAt", OffsetDateTime.now().minusDays(1));
        row.put("expiresAt", expiresAt);
        row.put("revokedAt", revokedAt);
        return row;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
