package com.game.backend.common.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.audit.retention")
public class AuditRetentionProperties {
    private boolean enabled = false;
    private Duration adminRetention = Duration.ofDays(180);
    private Duration serverRetention = Duration.ofDays(90);
    private int batchSize = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getAdminRetention() {
        return adminRetention;
    }

    public void setAdminRetention(Duration adminRetention) {
        this.adminRetention = adminRetention;
    }

    public Duration getServerRetention() {
        return serverRetention;
    }

    public void setServerRetention(Duration serverRetention) {
        this.serverRetention = serverRetention;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
