package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ServerAdminSecurityIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final UUID DEV_SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID LIMITED_SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRejectServerRequestsWithoutValidIdentityHeaders() throws Exception {
        UUID playerId = registerPlayer();
        Map<String, Object> body = matchProfileBuildBody(UUID.randomUUID(), playerId, "global", devServerBuildId());

        mockMvc.perform(postJson("/server/match-profile/build", body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(postJson("/server/match-profile/build", body)
                        .header("X-Server-Id", DEV_SERVER_ID.toString())
                        .header("X-Server-Certificate-Fingerprint", "wrong-fingerprint"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectRevokedAndExpiredServerIdentities() throws Exception {
        UUID playerId = registerPlayer();
        UUID revokedServerId = UUID.randomUUID();
        UUID expiredServerId = UUID.randomUUID();
        String revokedFingerprint = "revoked-" + revokedServerId;
        String expiredFingerprint = "expired-" + expiredServerId;
        insertServerIdentity(revokedServerId, "revoked", revokedFingerprint, "ds-revoked-test", OffsetDateTime.now().plusDays(1));
        insertServerIdentity(expiredServerId, "active", expiredFingerprint, "ds-expired-test", OffsetDateTime.now().minusDays(1));

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(UUID.randomUUID(), playerId, "global", "ds-revoked-test"))
                        .header("X-Server-Id", revokedServerId.toString())
                        .header("X-Server-Certificate-Fingerprint", revokedFingerprint))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(UUID.randomUUID(), playerId, "global", "ds-expired-test"))
                        .header("X-Server-Id", expiredServerId.toString())
                        .header("X-Server-Certificate-Fingerprint", expiredFingerprint))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectServerScopeRealmBuildAndMatchOwnershipViolations() throws Exception {
        UUID playerId = registerPlayer();

        mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody(UUID.randomUUID(), 1, UUID.randomUUID(), playerId, "loadout_applied"))
                        .header("X-Server-Id", LIMITED_SERVER_ID.toString())
                        .header("X-Server-Certificate-Fingerprint", limitedServerFingerprint())
                        .header("Idempotency-Key", "limited-scope:" + UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(UUID.randomUUID(), playerId, "realm.other", devServerBuildId()))
                        .headers(devServerHeaders()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SERVER_REALM_MISMATCH"));

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(UUID.randomUUID(), playerId, "global", "wrong-build"))
                        .headers(devServerHeaders()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SERVER_BUILD_MISMATCH"));

        UUID matchId = UUID.randomUUID();
        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(matchId, playerId, "global", devServerBuildId()))
                        .headers(devServerHeaders()))
                .andExpect(status().isOk());

        UUID otherServerId = UUID.randomUUID();
        String otherFingerprint = "other-full-" + otherServerId;
        insertServerIdentity(otherServerId, "active", otherFingerprint, "ds-other-full-test", OffsetDateTime.now().plusDays(1));

        mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody(UUID.randomUUID(), 2, matchId, playerId, "loadout_applied"))
                        .header("X-Server-Id", otherServerId.toString())
                        .header("X-Server-Certificate-Fingerprint", otherFingerprint)
                        .header("Idempotency-Key", "wrong-owner:" + UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MATCH_ASSIGNED_TO_ANOTHER_SERVER"));
    }

    @Test
    void shouldProtectAdminEndpointsWithAdminToken() throws Exception {
        mockMvc.perform(get("/admin/status/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/admin/status/overview")
                        .header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/admin/status/overview")
                        .header("X-Admin-Token", "dev-admin-token")
                        .header("X-Admin-Id", "security-test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backend.ok").value(true));
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "security_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode registered = json(
                mockMvc.perform(postJson("/auth/register", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isCreated())
                        .andReturn()
        );
        return UUID.fromString(registered.path("player_id").asText());
    }

    private Map<String, Object> matchProfileBuildBody(
            UUID matchId,
            UUID playerId,
            String realmId,
            String serverBuildId
    ) {
        return Map.of(
                "match_id", matchId.toString(),
                "player_id", playerId.toString(),
                "realm_id", realmId,
                "class_tag", "class.assault",
                "team_tag", "team.red",
                "weapon_preset_slot", 1,
                "outfit_preset_slot", 1,
                "supported_catalog_versions", List.of(activeCatalogVersion()),
                "preferred_catalog_version", activeCatalogVersion(),
                "server_build_id", serverBuildId
        );
    }

    private Map<String, Object> runtimeEventBody(
            UUID eventId,
            long eventSeq,
            UUID matchId,
            UUID playerId,
            String eventType
    ) {
        return Map.of(
                "event_id", eventId.toString(),
                "event_seq", eventSeq,
                "match_id", matchId.toString(),
                "event_type", eventType,
                "player_id", playerId.toString(),
                "payload_schema_version", 1,
                "occurred_at", OffsetDateTime.now().toString(),
                "payload", Map.of(
                        "source", "security_integration_test",
                        "event_type", eventType
                )
        );
    }

    private void insertServerIdentity(
            UUID serverId,
            String status,
            String fingerprint,
            String serverBuildId,
            OffsetDateTime expiresAt
    ) {
        jdbcTemplate.update(
                """
                    INSERT INTO server_identities(
                      server_id,
                      realm_id,
                      server_build_id,
                      certificate_fingerprint,
                      status,
                      allowed_scopes,
                      created_at,
                      expires_at
                    )
                    VALUES (?, 'global', ?, ?, ?, ARRAY[
                      'match_profile:read',
                      'runtime_event:write',
                      'runtime_preset_change:write',
                      'match_audit:write'
                    ], ?, ?)
                    """,
                serverId,
                serverBuildId,
                fingerprint,
                status,
                OffsetDateTime.now(),
                expiresAt
        );
    }

    private org.springframework.http.HttpHeaders devServerHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Server-Id", DEV_SERVER_ID.toString());
        headers.add("X-Server-Certificate-Fingerprint", devServerFingerprint());
        return headers;
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

    private String limitedServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                LIMITED_SERVER_ID
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
            String url,
            Object body
    ) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
