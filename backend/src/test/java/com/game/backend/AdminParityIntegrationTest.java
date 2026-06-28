package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "app.outbox.worker-enabled=false"
    }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminParityIntegrationTest {
    private static final String ADMIN_TOKEN = "dev-admin-token";
    private static final String PASSWORD = "password123";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final String WEAPON_ID = "weapon.ak12";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetCatalogPointer() {
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'previous',
                    rollout_percent = 0,
                    allow_new_matches = false,
                    allow_existing_matches = true
                WHERE realm_id = 'global'
                """
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'active',
                    rollout_percent = 100,
                    allow_new_matches = true,
                    allow_existing_matches = true
                WHERE realm_id = 'global'
                  AND catalog_version = 1
                """
        );
    }

    @Test
    void shouldExposeTzItemOperationEndpointsWithIdempotency() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);

        Map<String, Object> hideBody = itemOperationBody(playerId, catalogVersion, "hide weapon through explicit endpoint");
        String hideKey = UUID.randomUUID().toString();
        long auditBefore = adminAccessAuditCount(playerId, catalogVersion, "hide weapon through explicit endpoint");

        mockMvc.perform(postJson("/admin/items/hide", hideBody)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", hideKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.player_id").value(playerId.toString()))
            .andExpect(jsonPath("$.item_id").value(WEAPON_ID))
            .andExpect(jsonPath("$.hidden").value(true))
            .andExpect(jsonPath("$.player_can_use").value(false));

        assertThat(accessFlag(playerId, catalogVersion, "is_hidden")).isTrue();
        assertThat(adminAccessAuditCount(playerId, catalogVersion, "hide weapon through explicit endpoint"))
            .isEqualTo(auditBefore + 1);
        assertThat(adminAccessAuditHasRequestHash(playerId, catalogVersion, "hide weapon through explicit endpoint"))
            .isTrue();

        mockMvc.perform(postJson("/admin/items/hide", hideBody)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", hideKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true));

        mockMvc.perform(postJson("/admin/items/hide", itemOperationBody(playerId, catalogVersion, "different request body"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", hideKey))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        mockMvc.perform(postJson("/admin/items/reveal", itemOperationBody(playerId, catalogVersion, "reveal weapon"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(false))
            .andExpect(jsonPath("$.player_can_use").value(true));

        mockMvc.perform(postJson("/admin/items/shop-lock", itemOperationBody(playerId, catalogVersion, "lock weapon in shop"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locked_in_shop").value(true))
            .andExpect(jsonPath("$.unlock_hint_code").value("buy_in_shop"))
            .andExpect(jsonPath("$.player_can_use").value(false));

        mockMvc.perform(postJson("/admin/items/shop-unlock", itemOperationBody(playerId, catalogVersion, "unlock weapon from shop"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locked_in_shop").value(false))
            .andExpect(jsonPath("$.player_can_use").value(true));
    }

    @Test
    void shouldRebuildAccessProjectionAndInvalidatePlayerCache() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);

        mockMvc.perform(postJson("/admin/items/hide", itemOperationBody(playerId, catalogVersion, "hide before rebuild"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk());

        jdbcTemplate.update(
            """
                UPDATE player_item_access
                SET is_hidden = false,
                    unlock_hint_code = null
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """,
            playerId,
            WEAPON_ID,
            catalogVersion
        );
        assertThat(accessFlag(playerId, catalogVersion, "is_hidden")).isFalse();

        JsonNode rebuild = json(
            mockMvc.perform(postJson("/admin/access/rebuild-projection", Map.of(
                    "player_id", playerId.toString(),
                    "reason", "restore projection from ledger"
                ))
                    .header("X-Admin-Token", ADMIN_TOKEN)
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                .andExpect(jsonPath("$.items_rebuilt").isNumber())
                .andExpect(jsonPath("$.ledger_events_applied").isNumber())
                .andReturn()
        );

        assertThat(rebuild.path("items_rebuilt").asInt()).isGreaterThan(0);
        assertThat(accessFlag(playerId, catalogVersion, "is_hidden")).isTrue();

        mockMvc.perform(postJson("/admin/cache/invalidate-player", Map.of(
                    "player_id", playerId.toString(),
                    "reason", "manual cache invalidation smoke"
                ))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.player_id").value(playerId.toString()))
            .andExpect(jsonPath("$.stale_match_profiles").isNumber());
    }

    @Test
    void shouldRevokeServerIdentityThroughTzEndpoint() throws Exception {
        UUID serverId = UUID.randomUUID();
        insertServerIdentity(serverId);
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
            "server_id", serverId.toString(),
            "reason", "rotation test"
        );

        mockMvc.perform(postJson("/admin/server-identities/revoke", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.server_id").value(serverId.toString()))
            .andExpect(jsonPath("$.status").value("revoked"))
            .andExpect(jsonPath("$.updated").value(true));

        assertThat(serverIdentityStatus(serverId)).isEqualTo("revoked");

        mockMvc.perform(postJson("/admin/server-identities/revoke", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(true));

        mockMvc.perform(postJson("/admin/server-identities/revoke", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(false));
    }

    @Test
    void shouldRequireAndReplayIdempotencyForAdminControlActions() throws Exception {
        Map<String, Object> body = controlReasonBody("retry failed outbox from dashboard");

        mockMvc.perform(postJson("/admin/control/outbox/retry-failed", body)
                .header("X-Admin-Token", ADMIN_TOKEN))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(postJson("/admin/control/outbox/retry-failed", Map.of("reason", " "))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String idempotencyKey = UUID.randomUUID().toString();
        long auditBefore = adminControlAuditCount("outbox.retry_failed", "retry failed outbox from dashboard");

        MvcResult first = mockMvc.perform(postJson("/admin/control/outbox/retry-failed", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retried").isNumber())
            .andReturn();

        assertThat(adminControlAuditCount("outbox.retry_failed", "retry failed outbox from dashboard"))
            .isEqualTo(auditBefore + 1);
        assertThat(adminControlAuditHasRequestHash("outbox.retry_failed", "retry failed outbox from dashboard"))
            .isTrue();

        MvcResult replay = mockMvc.perform(postJson("/admin/control/outbox/retry-failed", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.retried").isNumber())
            .andReturn();

        assertThat(json(replay).path("retried").asInt())
            .isEqualTo(json(first).path("retried").asInt());
    }

    @Test
    void shouldUseCallerIdempotencyKeyForAdminControlWeaponAccess() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
            "weapon_id", WEAPON_ID,
            "catalog_version", catalogVersion,
            "action", "hide_item",
            "reason", "hide weapon through dashboard control"
        );

        mockMvc.perform(postJson("/admin/control/players/" + playerId + "/weapon-access", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true))
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(postJson("/admin/control/players/" + playerId + "/weapon-access", body)
                .header("X-Admin-Token", ADMIN_TOKEN)
                .header("Idempotency-Key", idempotencyKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true))
            .andExpect(jsonPath("$.duplicate").value(true));
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "adminparity_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private Map<String, Object> itemOperationBody(UUID playerId, long catalogVersion, String reason) {
        return Map.of(
            "player_id", playerId.toString(),
            "item_id", WEAPON_ID,
            "catalog_version", catalogVersion,
            "reason", reason
        );
    }

    private Map<String, Object> controlReasonBody(String reason) {
        return Map.of(
            "reason", reason,
            "comment", "admin parity test"
        );
    }

    private long weaponPresetCatalogVersion(UUID playerId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT catalog_version
                FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                """,
            Long.class,
            playerId,
            CLASS_TAG,
            WEAPON_PRESET_SLOT
        );
    }

    private boolean accessFlag(UUID playerId, long catalogVersion, String columnName) {
        return jdbcTemplate.queryForObject(
            """
                SELECT %s
                FROM player_item_access
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """.formatted(columnName),
            Boolean.class,
            playerId,
            WEAPON_ID,
            catalogVersion
        );
    }

    private long adminAccessAuditCount(UUID playerId, long catalogVersion, String reason) {
        Long count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM admin_audit_events
                WHERE action = 'player_access.item_update'
                  AND target_type = 'player_item_access'
                  AND target_id = ?
                  AND result = 'success'
                  AND request_hash IS NOT NULL
                  AND request_hash <> ''
                  AND payload->>'reason' = ?
                  AND (payload->>'catalog_version')::bigint = ?
                  AND jsonb_exists(payload, 'ledger_event_id')
                """,
            Long.class,
            playerId + ":" + WEAPON_ID + ":" + catalogVersion,
            reason,
            catalogVersion
        );
        return count == null ? 0 : count;
    }

    private boolean adminAccessAuditHasRequestHash(UUID playerId, long catalogVersion, String reason) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM admin_audit_events
                  WHERE action = 'player_access.item_update'
                    AND target_type = 'player_item_access'
                    AND target_id = ?
                    AND result = 'success'
                    AND request_hash ~ '^[0-9a-f]{64}$'
                    AND payload->>'reason' = ?
                    AND (payload->>'catalog_version')::bigint = ?
                )
                """,
            Boolean.class,
            playerId + ":" + WEAPON_ID + ":" + catalogVersion,
            reason,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    private long adminControlAuditCount(String action, String reason) {
        Long count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM admin_audit_events
                WHERE action = ?
                  AND result = 'success'
                  AND payload->>'reason' = ?
                """,
            Long.class,
            action,
            reason
        );
        return count == null ? 0 : count;
    }

    private boolean adminControlAuditHasRequestHash(String action, String reason) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM admin_audit_events
                  WHERE action = ?
                    AND result = 'success'
                    AND request_hash ~ '^[0-9a-f]{64}$'
                    AND payload->>'reason' = ?
                    AND payload->>'comment' = 'admin parity test'
                )
                """,
            Boolean.class,
            action,
            reason
        );
        return Boolean.TRUE.equals(exists);
    }

    private void insertServerIdentity(UUID serverId) {
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
                VALUES (?, 'global', ?, ?, 'active', ARRAY['runtime_event:write'], ?, ?)
                """,
            serverId,
            "ds-admin-parity-test",
            "fingerprint-" + serverId,
            OffsetDateTime.now(),
            OffsetDateTime.now().plusDays(1)
        );
    }

    private String serverIdentityStatus(UUID serverId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM server_identities WHERE server_id = ?",
            String.class,
            serverId
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
        String url,
        Object body
    ) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = post(url)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
        if (url.startsWith("/admin/")) {
            builder.header("X-Admin-Confirm", "true");
        }
        return builder;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
