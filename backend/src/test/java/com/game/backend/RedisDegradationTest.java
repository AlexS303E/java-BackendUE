package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.access.api.AccessResponse;
import com.game.backend.admin.application.AdminStatusService;
import com.game.backend.admin.repository.AdminRepository;
import com.game.backend.cache.BackendCacheProperties;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.api.CatalogSnapshotResponse;
import com.game.backend.matchprofile.api.DependencyRevisionsDto;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisDegradationTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000333");

    @Test
    void cacheServiceShouldDegradeToMissesWhenRedisIsDown() {
        StringRedisTemplate redisTemplate = redisDownTemplate();
        RedisCacheService cacheService = new RedisCacheService(
            redisTemplate,
            new ObjectMapper(),
            cacheProperties(),
            new SimpleMeterRegistry()
        );

        Optional<CatalogSnapshotResponse> catalog = cacheService.getCatalogSnapshot("global", 1L);
        Optional<AccessResponse> access = cacheService.getAccess(PLAYER_ID, 1L, 1L);
        Optional<MatchProfileResponse> matchProfile = cacheService.getMatchProfile(
            PLAYER_ID,
            "global",
            "class.assault",
            "team.red",
            1,
            1,
            1L,
            1L,
            1L,
            1L
        );
        Optional<Boolean> allowsNewMatches = cacheService.getCatalogAllowsNewMatches("global", 1L);

        assertThat(catalog).isEmpty();
        assertThat(access).isEmpty();
        assertThat(matchProfile).isEmpty();
        assertThat(allowsNewMatches).isEmpty();
        assertThatCode(() -> cacheService.putCatalogSnapshot(
            new CatalogSnapshotResponse("global", 1L, List.of(), List.of(), List.of())
        )).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putAccess(
            new AccessResponse(PLAYER_ID, 1L, 1L, List.of())
        )).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putMatchProfile(matchProfileResponse()))
            .doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putCatalogAllowsNewMatches("global", 1L, true))
            .doesNotThrowAnyException();
        assertThatCode(() -> cacheService.evictCatalogSnapshots("global"))
            .doesNotThrowAnyException();
        assertThatCode(() -> cacheService.evictPlayerAccess(PLAYER_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void adminOverviewShouldReportRedisDownWithoutFailingDashboard() {
        AdminRepository repository = mock(AdminRepository.class);
        when(repository.databasePingOk()).thenReturn(true);
        when(repository.activeCatalog()).thenReturn(Map.of("activeVersion", 1L));
        when(repository.countRunningMatches()).thenReturn(2L);
        when(repository.countPendingRuntimeConflicts()).thenReturn(1L);
        when(repository.outboxStatusCounts()).thenReturn(Map.of(
            "pending", 0L,
            "failed", 0L,
            "processed", 3L
        ));
        when(repository.oldestPendingOutboxCreatedAt()).thenReturn(null);

        AdminStatusService service = new AdminStatusService(repository, redisDownTemplate());

        Map<String, Object> overview = service.overview();

        @SuppressWarnings("unchecked")
        Map<String, Object> infrastructure = (Map<String, Object>) overview.get("infrastructure");
        assertThat(infrastructure.get("databaseOk")).isEqualTo(true);
        assertThat(infrastructure.get("redisOk")).isEqualTo(false);
    }

    private StringRedisTemplate redisDownTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("redis down"));
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
        when(redisTemplate.opsForSet()).thenThrow(new RedisConnectionFailureException("redis down"));
        return redisTemplate;
    }

    private BackendCacheProperties cacheProperties() {
        BackendCacheProperties properties = new BackendCacheProperties();
        properties.setEnabled(true);
        properties.setCatalogSnapshotTtl(Duration.ofMinutes(10));
        properties.setAccessTtl(Duration.ofMinutes(5));
        properties.setMatchProfileTtl(Duration.ofMinutes(10));
        return properties;
    }

    private MatchProfileResponse matchProfileResponse() {
        return new MatchProfileResponse(
            1,
            PLAYER_ID,
            "global",
            1L,
            "class.assault",
            "team.red",
            1,
            1,
            List.of(),
            List.of(),
            List.of(),
            new DependencyRevisionsDto(1L, 1L, 1L, 1L)
        );
    }
}
