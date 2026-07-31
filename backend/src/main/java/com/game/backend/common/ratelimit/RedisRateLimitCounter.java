package com.game.backend.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisRateLimitCounter implements RateLimitCounter {
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
        "local count = redis.call('INCR', KEYS[1])\n"
            + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
            + "return count",
        Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long count = redisTemplate.execute(
            INCREMENT_WITH_TTL,
            List.of(key),
            Long.toString(Math.max(1000, ttl.toMillis()))
        );
        if (count == null) {
            throw new DataAccessResourceFailureException("Redis rate-limit script returned no counter value");
        }
        return count;
    }
}
