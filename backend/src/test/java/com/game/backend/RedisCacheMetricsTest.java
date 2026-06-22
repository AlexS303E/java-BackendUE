package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.BackendCacheProperties;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.api.CatalogSnapshotResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCacheMetricsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void shouldRecordCacheHitMissAndErrorCounters() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);

        CatalogSnapshotResponse response = new CatalogSnapshotResponse("global", 1L, List.of(), List.of(), List.of());
        String key = "ue:catalog:snapshot:global:1";
        when(operations.get(key))
            .thenReturn(objectMapper.writeValueAsString(response))
            .thenReturn(null)
            .thenThrow(new RedisConnectionFailureException("redis down"));

        RedisCacheService cacheService = new RedisCacheService(
            redisTemplate,
            objectMapper,
            cacheProperties(),
            meterRegistry
        );

        assertThat(cacheService.getCatalogSnapshot("global", 1L)).isPresent();
        assertThat(cacheService.getCatalogSnapshot("global", 1L)).isEmpty();
        assertThat(cacheService.getCatalogSnapshot("global", 1L)).isEmpty();

        assertThat(cacheCounter("catalog_snapshot", "hit")).isEqualTo(1.0);
        assertThat(cacheCounter("catalog_snapshot", "miss")).isEqualTo(1.0);
        assertThat(cacheCounter("catalog_snapshot", "error")).isEqualTo(1.0);
    }

    private double cacheCounter(String cacheName, String result) {
        return meterRegistry.get("backend.cache.requests")
            .tag("cache", cacheName)
            .tag("result", result)
            .counter()
            .count();
    }

    private BackendCacheProperties cacheProperties() {
        BackendCacheProperties properties = new BackendCacheProperties();
        properties.setEnabled(true);
        properties.setCatalogSnapshotTtl(Duration.ofMinutes(10));
        properties.setAccessTtl(Duration.ofMinutes(5));
        properties.setMatchProfileTtl(Duration.ofMinutes(10));
        return properties;
    }
}
