package com.game.backend.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@ConditionalOnProperty(prefix = "app.audit.retention", name = "enabled", havingValue = "true")
public class AuditRetentionService {
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditRetentionProperties properties;
    private final Clock clock;

    @Autowired
    public AuditRetentionService(JdbcTemplate jdbcTemplate, AuditRetentionProperties properties) {
        this(jdbcTemplate, properties, Clock.systemUTC());
    }

    AuditRetentionService(JdbcTemplate jdbcTemplate, AuditRetentionProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
        validateProperties(properties);
    }

    @Scheduled(fixedDelayString = "${app.audit.retention.cleanup-interval-ms:86400000}")
    public void cleanupExpiredAuditEvents() {
        int batchSize = Math.max(1, properties.getBatchSize());
        OffsetDateTime now = OffsetDateTime.now(clock);

        int adminDeleted = deleteExpired(
            "admin_audit_events",
            now.minus(properties.getAdminRetention()),
            batchSize
        );
        int serverDeleted = deleteExpired(
            "server_audit_events",
            now.minus(properties.getServerRetention()),
            batchSize
        );

        if (adminDeleted > 0 || serverDeleted > 0) {
            log.info(
                "Audit retention cleanup deleted admin_events={} server_events={}",
                adminDeleted,
                serverDeleted
            );
        }
    }

    private int deleteExpired(String tableName, OffsetDateTime cutoff, int batchSize) {
        Integer deleted = jdbcTemplate.queryForObject(
            """
                WITH deleted AS (
                  DELETE FROM %s
                  WHERE event_id IN (
                    SELECT event_id
                    FROM %s
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                  )
                  RETURNING 1
                )
                SELECT count(*)
                FROM deleted
                """.formatted(tableName, tableName),
            Integer.class,
            cutoff,
            batchSize
        );
        return deleted == null ? 0 : deleted;
    }

    private void validateProperties(AuditRetentionProperties properties) {
        requirePositive("app.audit.retention.admin-retention", properties.getAdminRetention());
        requirePositive("app.audit.retention.server-retention", properties.getServerRetention());
        if (properties.getBatchSize() < 1) {
            throw new IllegalStateException("app.audit.retention.batch-size must be at least 1");
        }
    }

    private void requirePositive(String propertyName, java.time.Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(propertyName + " must be positive");
        }
    }
}
