package com.game.backend.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    private boolean enabled = false;
    private Duration window = Duration.ofMinutes(1);
    private int authLimit = 60;
    private int serverLimit = 600;
    private int adminLimit = 120;
    private boolean failClosedOnRedisError = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getAuthLimit() {
        return authLimit;
    }

    public void setAuthLimit(int authLimit) {
        this.authLimit = authLimit;
    }

    public int getServerLimit() {
        return serverLimit;
    }

    public void setServerLimit(int serverLimit) {
        this.serverLimit = serverLimit;
    }

    public int getAdminLimit() {
        return adminLimit;
    }

    public void setAdminLimit(int adminLimit) {
        this.adminLimit = adminLimit;
    }

    public boolean isFailClosedOnRedisError() {
        return failClosedOnRedisError;
    }

    public void setFailClosedOnRedisError(boolean failClosedOnRedisError) {
        this.failClosedOnRedisError = failClosedOnRedisError;
    }
}
