package com.game.backend.common.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "app.outbox.worker-enabled=false",
        "app.audit.retention.enabled=false",
        "app.server-auth.mtls.enabled=false",
        "app.server-auth.mtls.require-private-port=false",
        "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
    }
)
@ActiveProfiles("local")
class AuditRetentionServiceIntegrationTest {
    private static final UUID SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRetentionTestRows() {
        jdbcTemplate.update("DELETE FROM server_audit_events WHERE action = 'retention.test'");
        jdbcTemplate.update("DELETE FROM admin_audit_events WHERE actor_id = 'retention-test-admin'");
    }

    @Test
    void shouldDeleteOnlyExpiredAuditEventsWithinBatchLimits() {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-05-16T00:00:00Z"), ZoneOffset.UTC);
        UUID expiredAdminA = UUID.randomUUID();
        UUID expiredAdminB = UUID.randomUUID();
        UUID freshAdmin = UUID.randomUUID();
        UUID expiredServer = UUID.randomUUID();
        UUID freshServer = UUID.randomUUID();

        insertAdminAudit(expiredAdminA, now.minusDays(181));
        insertAdminAudit(expiredAdminB, now.minusDays(182));
        insertAdminAudit(freshAdmin, now.minusDays(30));
        insertServerAudit(expiredServer, now.minusDays(91));
        insertServerAudit(freshServer, now.minusDays(30));

        AuditRetentionProperties properties = new AuditRetentionProperties();
        properties.setAdminRetention(Duration.ofDays(180));
        properties.setServerRetention(Duration.ofDays(90));
        properties.setBatchSize(1);

        AuditRetentionService service = new AuditRetentionService(
            jdbcTemplate,
            properties,
            Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        service.cleanupExpiredAuditEvents();

        assertThat(auditEventExists("admin_audit_events", expiredAdminA)).isTrue();
        assertThat(auditEventExists("admin_audit_events", expiredAdminB)).isFalse();
        assertThat(auditEventExists("admin_audit_events", freshAdmin)).isTrue();
        assertThat(auditEventExists("server_audit_events", expiredServer)).isFalse();
        assertThat(auditEventExists("server_audit_events", freshServer)).isTrue();

        service.cleanupExpiredAuditEvents();

        assertThat(auditEventExists("admin_audit_events", expiredAdminA)).isFalse();
        assertThat(auditEventExists("admin_audit_events", freshAdmin)).isTrue();
        assertThat(auditEventExists("server_audit_events", freshServer)).isTrue();
    }

    @Test
    void shouldRejectUnsafeRetentionConfiguration() {
        AuditRetentionProperties properties = new AuditRetentionProperties();
        properties.setAdminRetention(Duration.ZERO);
        properties.setServerRetention(Duration.ofDays(90));
        properties.setBatchSize(100);

        assertThatThrownBy(() -> new AuditRetentionService(
            jdbcTemplate,
            properties,
            Clock.systemUTC()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin-retention");
    }

    private void insertAdminAudit(UUID eventId, OffsetDateTime createdAt) {
        jdbcTemplate.update(
            """
                INSERT INTO admin_audit_events(
                  event_id,
                  actor_id,
                  action,
                  target_type,
                  target_id,
                  request_hash,
                  payload,
                  result,
                  created_at
                )
                VALUES (?, 'retention-test-admin', 'retention.test', 'test', ?, null, '{}'::jsonb, 'success', ?)
                """,
            eventId,
            eventId.toString(),
            createdAt
        );
    }

    private void insertServerAudit(UUID eventId, OffsetDateTime createdAt) {
        jdbcTemplate.update(
            """
                INSERT INTO server_audit_events(
                  event_id,
                  server_id,
                  match_id,
                  action,
                  scope,
                  result,
                  payload,
                  created_at
                )
                VALUES (?, ?, null, 'retention.test', 'match_audit:write', 'success', '{}'::jsonb, ?)
                """,
            eventId,
            SERVER_ID,
            createdAt
        );
    }

    private boolean auditEventExists(String tableName, UUID eventId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM " + tableName + " WHERE event_id = ?)",
            Boolean.class,
            eventId
        ));
    }
}
