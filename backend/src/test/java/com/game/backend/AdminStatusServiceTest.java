package com.game.backend;

import com.game.backend.admin.application.AdminStatusService;
import com.game.backend.admin.repository.AdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        Map<String, Object> overview = service.overview();

        assertThat(overview).containsKeys("backend", "infrastructure", "catalog", "runtime", "outbox");
        verify(repository).databasePingOk();
        verify(repository).activeCatalog();
        verify(repository).countRunningMatches();
        verify(repository).countPendingRuntimeConflicts();
        verify(repository).outboxStatusCounts();
        verify(repository).oldestPendingOutboxCreatedAt();
        verify(repository, never()).listServers();
        verify(repository, never()).listMatches();
        verify(repository, never()).listRecentAuditEvents();
        verify(repository, never()).searchPlayersByLogin(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void blankPlayerSearchShouldNotHitDatabase() {
        assertThat(service.searchPlayers("  ")).isEqualTo(List.of());

        verify(repository, never()).findPlayer(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).searchPlayersByLogin(org.mockito.ArgumentMatchers.anyString());
    }

    private StringRedisTemplate redisDownTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("redis down"));
        return redisTemplate;
    }
}
