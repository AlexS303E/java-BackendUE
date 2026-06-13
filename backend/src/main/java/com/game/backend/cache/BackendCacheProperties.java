package com.game.backend.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.cache")
public class BackendCacheProperties {
    private boolean enabled = true;
    private Duration catalogSnapshotTtl = Duration.ofMinutes(10);
    private Duration accessTtl = Duration.ofMinutes(5);
    private Duration matchProfileTtl = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCatalogSnapshotTtl() {
        return catalogSnapshotTtl;
    }

    public void setCatalogSnapshotTtl(Duration catalogSnapshotTtl) {
        this.catalogSnapshotTtl = catalogSnapshotTtl;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getMatchProfileTtl() {
        return matchProfileTtl;
    }

    public void setMatchProfileTtl(Duration matchProfileTtl) {
        this.matchProfileTtl = matchProfileTtl;
    }
}
