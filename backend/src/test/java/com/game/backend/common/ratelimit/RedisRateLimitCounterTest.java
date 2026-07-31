package com.game.backend.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRateLimitCounterTest {
    @Test
    void shouldClassifyMissingScriptResultAsRedisAvailabilityFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(null);
        RedisRateLimitCounter counter = new RedisRateLimitCounter(redisTemplate);

        assertThatThrownBy(() -> counter.increment("rate-limit:auth", Duration.ofMinutes(1)))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("no counter value");
    }
}
