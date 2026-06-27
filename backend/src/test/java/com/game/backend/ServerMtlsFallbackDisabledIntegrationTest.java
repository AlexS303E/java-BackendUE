package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ServerMtlsFallbackDisabledIntegrationTest {
    private static final UUID DEV_SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldRejectHeaderFingerprintFallbackWhenDisabledAndAuditKnownServer() throws Exception {
        long auditBefore = authenticationDeniedAuditCount("mtls_disabled_and_header_fallback_forbidden");
        double metricBefore = authenticationDeniedMetric("mtls_disabled_and_header_fallback_forbidden");

        mockMvc.perform(post("/server/match-profile/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(matchProfileBuildBody()))
                        .header("X-Server-Id", DEV_SERVER_ID.toString())
                        .header("X-Server-Certificate-Fingerprint", devServerFingerprint()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(authenticationDeniedAuditCount("mtls_disabled_and_header_fallback_forbidden"))
                .isEqualTo(auditBefore + 1);
        assertThat(authenticationDeniedMetric("mtls_disabled_and_header_fallback_forbidden"))
                .isEqualTo(metricBefore + 1.0);
    }

    private Map<String, Object> matchProfileBuildBody() {
        long activeCatalogVersion = activeCatalogVersion();
        return Map.ofEntries(
                Map.entry("match_id", UUID.randomUUID().toString()),
                Map.entry("player_id", UUID.randomUUID().toString()),
                Map.entry("realm_id", "global"),
                Map.entry("class_tag", "class.assault"),
                Map.entry("team_tag", "team.red"),
                Map.entry("weapon_preset_slot", 1),
                Map.entry("outfit_preset_slot", 1),
                Map.entry("supported_catalog_versions", List.of(activeCatalogVersion)),
                Map.entry("preferred_catalog_version", activeCatalogVersion),
                Map.entry("server_build_id", devServerBuildId()),
                Map.entry("game_mode_id", "tdm")
        );
    }

    private long authenticationDeniedAuditCount(String reason) {
        Long count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM server_audit_events
                    WHERE server_id = ?
                      AND action = 'server_auth.authentication_denied'
                      AND result = 'denied'
                      AND payload->>'reason' = ?
                    """,
                Long.class,
                DEV_SERVER_ID,
                reason
        );
        return count == null ? 0 : count;
    }

    private double authenticationDeniedMetric(String reason) {
        Counter counter = meterRegistry.find("backend.server_auth.denials")
                .tag("reason", reason)
                .tag("scope", "match_profile:read")
                .tag("path", "/server/match-profile/build")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
        );
    }

    private String devServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
        );
    }

    private long activeCatalogVersion() {
        return jdbcTemplate.queryForObject(
                """
                    SELECT catalog_version
                    FROM catalog_deployments
                    WHERE realm_id = 'global'
                      AND deployment_state = 'active'
                      AND allow_new_matches = true
                    ORDER BY catalog_version DESC
                    LIMIT 1
                    """,
                Long.class
        );
    }
}
