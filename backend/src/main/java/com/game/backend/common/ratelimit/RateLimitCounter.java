package com.game.backend.common.ratelimit;

import java.time.Duration;

public interface RateLimitCounter {
    long increment(String key, Duration ttl);
}
